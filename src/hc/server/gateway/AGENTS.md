# hc.server.gateway — Sente + 内部 AG-UI 网关

> 权威顺序：根 `AGENTS.md` §7.3 > `src/AGENTS.md` > `src/hc/server/AGENTS.md` > 本文件。
> 本包只做适配。业务状态属于 Owner，字节属于 blob store。

## 1 责任与边界

网关把 Owner 的当前态与命令入口接到浏览器，同时保持三条边界：

1. **同步边界**：每个新订阅得到一份原子的 `STATE_SNAPSHOT` 与
   `{epoch, seq}`；之后每个状态事件消费下一个序号。旧订阅、旧纪元和
   落后订阅不得收到状态增量。
2. **命令边界**：浏览器命令只携带 `command-id`、`command-epoch` 与
   `payload`。网关检查订阅代号后交给 `OwnerPort`；命令是否接受、是否
   重放、是否因纪元退休而拒绝，全部由 Owner 的 `hc.server.commands`
   决定。网关只回传 `CUSTOM :hc/command-receipt`。
3. **字节边界**：Sente 只传 ID、状态与就绪通知。日志、附件与导出包走
   `/blobs/*` 的独立 HTTP 通道；超限的 `STATE_SNAPSHOT` / `STATE_DELTA`
   序列化为 EDN 大对象，经 `/sync-objects/<token>` 传输，Sente 只收到
   引用事件。单条 Sente 消息限制 1 MiB，永不提高。

网关不绑定生产 HTTP 服务器。调用方拥有 Jetty/http-kit，并注入 Sente 的
`IServerChanAdapter`。`hc.server.gateway.jdk-http` 只服务开发与真实 HTTP
冒烟，使用 JDK 自带 `com.sun.net.httpserver`，不新增依赖。

## 2 文件与命名空间

```text
src/hc/server/gateway.clj          门面：start!/stop!/publish!/broadcast!/handler
src/hc/server/gateway/feed.clj     原子 epoch/seq、订阅、stale、ack
src/hc/server/gateway/sente.clj    Sente channel socket 与接收循环
src/hc/server/gateway/sync_object.clj 大状态对象：EDN、摘要、注册表与引用
src/hc/server/gateway/http.clj     Ring 路由：/chsk、/blobs、/healthz、fallback
src/hc/server/gateway/blob.clj     Blob 下载/上传、Range、ETag
src/hc/server/gateway/ports.clj    OwnerPort、BlobStorePort 与适配器
src/hc/server/gateway/auth.clj     loopback/同源 Origin、Host 与 403 中间件
src/hc/server/gateway/patch.cljc   服务端 RFC 6902 applier
src/hc/server/gateway/diff.clj     状态树 RFC 6902 增量计算（仅 add/remove/replace）
src/hc/server/gateway/jdk_http.clj JDK HTTP 宿主与 Sente Ajax adapter
```

依赖方向：`gateway.*` 只依赖 Sente、Transit 与自身；不 require
`hc.client.*`，也不 require 具体存储引擎。Owner 与 store 通过
`hc.server.gateway.ports` 注入。

## 3 公共接口

```clojure
(require '[hc.server.gateway :as gateway])

(def gw
  (gateway/start!
   {:sch-adapter (sente-http-kit/get-sch-adapter) ; 必需
    :owner       owner-port                      ; 可选
    :store       blob-port                       ; 可选
    :fallback    app-ring-handler                ; 可选
    :epoch       1
    :snapshot    {}
    :max-pending 64
    :max-upload-bytes (* 100 1024 1024)
    :max-sente-body   (* 1024 1024)        ; 只可调低，1 MiB 为硬上限
    :max-message-bytes (* 1024 1024)        ; 出站 Sente 边界，只可调低
    :max-sente-message-bytes (* 1024 1024)  ; 入站事件边界，只可调低
    ;; 应用装配从配置 :gateway 读取；必须是 set 或 :all
    :allowed-origins #{"http://127.0.0.1:7575"}
    :trusted-hosts   #{"127.0.0.1" "localhost" "::1"}
    :allow-remote?   false
    :now-fn      #(System/currentTimeMillis)}))

(gateway/handler gw)   ; Ring handler，供调用方的 HTTP 服务器挂载
(gateway/stop! gw)
```

`start!` 只启动 Sente 接收循环，不监听端口。`stop!` 退订全部订阅并关闭
接收循环，返回 Sente 的 stop 证据：只有接收循环在时限内排空才
`:stopped? true`。未排空时应用 `system/stop!` 记录失败且不关闭 store；
再次调用 `stop!` 会重试并重新观察排空。

应用装配从配置 `:gateway` 读取这些值；应用默认 `:max-pending` 为 4096，
直接调用网关时 `feed/create` 的默认值为 64。

### 3.1 发布

```clojure
(gateway/publish! gw {:type :state-snapshot
                      :snapshot tree
                      :new-epoch? true})

(gateway/publish! gw {:type :state-delta
                      :delta [{"op" "replace"
                               "path" "/session~1by-id/s1/status"
                               "value" "idle"}]})

(gateway/publish! gw {:type :messages-snapshot
                      :messages messages
                      :hc/roles ["user" "assistant"]})

(gateway/broadcast! gw {:type "RUN_STARTED" :thread-id "t1"})
```

- `:state-snapshot` + `:new-epoch? true` 推进 epoch、把 seq 归零，并把新
  快照发给所有存活订阅。旧纪元的迟到事件由客户端的订阅代号与 epoch 丢弃。
- `:state-snapshot` 不带 `:new-epoch?` 时消费下一个 seq。完整快照可以治愈
  落后订阅；增量与 `MESSAGES_SNAPSHOT` 不能。
- `:state-delta` 先应用 JSON Patch。补丁失败时 epoch、seq 与树都不变。
- `:messages-snapshot` 的 `:messages` 是服务器权威完整列表；网关把它写成
  规范 `:messages` 形式，并删除旧的 `:message/by-id` / `:message/order`。
  `:hc/roles`、`:hc/clear-roles`、`:hc/epoch-rebuild?`、`:hc/order` 原样
  透传。
- `broadcast!` 只发非状态事件，不消费 seq；每个副本带订阅者自己的
  `:subscription-id`。

所有状态事件都带 `:type`、`:epoch`、`:seq`、`:at`、`:subscription-id`。
`publish!` 返回 `{:ok? true :epoch :seq :delivered ...}` 或错误。

应用发布器不在每个定时 tick 重发完整快照。它把当前树与上一份成功发布的
树比较：无变化时不消费 seq；有变化且增量事件不大于完整快照时发送
`STATE_DELTA`；订阅基线、resync、首次发布或增量不划算时才发送
`STATE_SNAPSHOT`。增量只携带变化字段，消息正文不因增量而被删除。单条
Sente 事件仍受 1 MiB 边界约束。超限的状态事件不会提高上限，也不会被
静默丢弃：网关先把完整树或补丁序列化为 EDN 大对象，再发送引用事件
（见 §3.2）。没有附加 blob store 时仍返回 `:SENTE_MESSAGE_TOO_LARGE`，
由调用方记录为 `:publish-error`，feed 的 epoch/seq 不推进。

### 3.2 大状态对象（`STATE_SNAPSHOT` / `STATE_DELTA`）

当一条状态事件的估算大小超过 `:max-message-bytes` 时，网关执行以下
顺序，并持有 `:publish-lock` 使所有发布与基线捕获串行化：

1. 校验载荷形状：快照必须是 map，增量必须是 JSON Patch 操作序列。
2. 用 `pr-str` 序列化为 EDN，并对**实际写入与摘要所用的 UTF-8 字节**做
   一次 EDN 往返校验；不可往返的值直接返回
   `:STATE_OBJECT_ENCODING_FAILED`，不发送半可读对象。校验必须针对字节而
   不是编码前的 String：`String.getBytes` 会把孤立代理项静默替换为 `?`，
   只校验 String 会让客户端读到与 feed 接受值不同的对象。打印成 reader
   macro 的值同样因无法往返而被拒绝，不会被读取执行。
   只接受 ClojureScript EDN reader 能原样表示的类型与精度：ratio、
   BigDecimal、超出 JavaScript 安全整数范围的 BigInt、character、regex
   等即使 JVM 可以往返也拒绝，避免客户端把值静默转成 number/string。
3. 通过 `BlobStorePort` 写入内容寻址的 blob；超过
   `:max-state-object-bytes` 返回 `:STATE_OBJECT_TOO_LARGE`。
4. 计算域分隔摘要 `sha256(object-tag || bytes)`，其中
   `object-tag` 是 `"hc/state-object/v1\n"`；该摘要不是 blob 内容 ID。
5. 在进程内注册表登记随机 128 位 token，并返回引用：

```clojure
{:token "32-hex"
 :digest "sha256-<64-hex>"
 :size 12345
 :encoding "edn"
 :kind :state-snapshot        ; 或 :state-delta
 :epoch 7
 :seq 42}
```

6. feed 先应用快照/补丁并分配 epoch/seq，再把引用作为
   `:snapshot-ref` 或 `:delta-ref` 放进同一顺序事件；引用中的
   `:epoch` / `:seq` 必须与 feed 实际分配值一致，否则 feed 返回
   `:STATE_OBJECT_BOUNDARY_MISMATCH` 且不改变任何状态。
7. 订阅基线与 resync 若当前树超限，也走同一对象路径；同一
   `[kind epoch seq blob-id]` 复用同一 token，多个订阅者共享对象。

客户端通过 `GET /sync-objects/<token>` 取得字节。响应是
`application/edn; charset=utf-8`、`Cache-Control: no-store, private`，
不支持 Range。token 只在当前 feed epoch 内有效；旧 epoch 的 token 返回
`410 :SYNC_OBJECT_STALE_EPOCH`，未知或被 LRU 淘汰的 token 返回 404。
错误体与其他网关路由一致，为
`{:error <code> :message ... :details {...}}`；`:details` 携带
`:epoch` / `:current-epoch` / `:action` 等诊断，绝不回显 token。
注册表默认保留最近 128 个引用（`:max-state-objects`），按插入顺序淘汰；
它不拥有 feed 权威，重启后 token 失效，客户端按正常重连取得新基线。
`:max-state-objects` 必须为正；0 或负数在写入 blob 之前返回
`:STATE_OBJECT_TOO_LARGE`，不占用存储，也不推进 feed。

引用事件本身仍受 1 MiB 边界约束；若配置的上限连引用信封都容纳不下，
发布返回 `:SENTE_MESSAGE_TOO_LARGE`，不会发出一个 packer 必然拒绝的
事件。旧 epoch 的迟到引用由客户端的订阅代号与 epoch 检查丢弃；服务端
路由同时拒绝旧 epoch token。

### 3.3 订阅与确认

Sente 连接建立后，客户端发送：

```clojure
[:hc/subscribe {:subscription-id 1}]
```

网关为该 uid 建立或替换订阅，推送基线 `STATE_SNAPSHOT`，并立即回执
`{:ok? true :subscription-id 1 :epoch ... :seq ...}`。同一 uid 使用新的
`subscription-id` 会退休旧订阅；旧订阅之后不再收到状态事件，也不能提交命令。

可选确认：

```clojure
[:hc/ack {:subscription-id 1 :seq 7}]
```

第一次确认把订阅切换为 ack 模式。若服务器领先超过 `:max-pending`，下一次
发布把该订阅标为 stale，并推送
`CUSTOM {:name "hc/resync-required"}`。stale 订阅不阻塞发布；它必须发送
`[:hc/resync {:subscription-id 1}]` 或重新订阅来取得新基线。

发送函数抛异常、订阅被替换、序号缺口都会使订阅 stale。完整快照会治愈
stale 订阅；增量不会。

嵌入客户端使用 `gateway/subscribe!` / `gateway/resync!` 时也走同一
`:publish-lock` 与超限对象路径：基线超过 `:max-message-bytes` 时发送
`:snapshot-ref`，不会内联一个 packer 必然拒绝的完整树。

## 4 命令合同

```clojure
[:hc/command {:command-id "c-1"
              :command-epoch 3
              :subscription-id 1
              :payload {:text "hello"}}]
```

处理顺序：

1. 订阅代号必须等于该 uid 的当前代号；否则回执
   `{:ok? false :error {:code :STALE_SUBSCRIPTION ...}}`，不触达 Owner。
2. 网关立即回传输层确认 `{:ok? true :status :submitted ...}`。这不是领域
   终态。
3. 网关调用 `(submit-command! owner request reply-fn)`。Owner 必须在串行
   步骤中调用 `hc.server.commands/accept`，并调用一次 `reply-fn`。
4. 网关把 Owner 的结果收窄为 `:ok?`、`:receipt`、`:replayed?`、`:error`、
   `:effects`、`:status`，附上 `command-id` 与 `command-epoch`，以
   `CUSTOM :hc/command-receipt` 推送。

回执查询：

```clojure
[:hc/receipt {:command-id "c-1"
              :session-id "s-1"}] ; app commands omit :session-id
```

有 `?reply-fn` 时直接回复；否则推送 `CUSTOM :hc/command-receipt`。Owner
按 `:scope`（或 `:session-id`，缺省 `"app"`）查询对应持久门；返回 nil 表示
该作用域当前纪元没有此命令，客户端应重新读取状态而不是重放。不同会话的
回执绝不互相回退。

Owner 抛异常时回执 `:COMMAND_SUBMIT_FAILED`；Owner 未注入时回执
`:OWNER_UNAVAILABLE`。网关不自行执行、去重或重试命令。

## 5 端口

```clojure
(require '[hc.server.gateway.ports :as ports])

(def owner
  (ports/owner-port
   (fn [request reply-fn] ...)
   (fn [query] ...))) ; query 是 command-id 字符串或作用域查询 map

(def store (ports/blob-store-port "/var/lib/herdchestra/blobs"))
```

- `OwnerPort`：`submit-command!`、`command-receipt`。
- `BlobStorePort`：`blob-metadata`、`open-blob`、`put-blob!`、
  `blob-exists?`。
- `blob-store-port` 把 `hc.server.blob-store` 根目录适配成端口；缺失对象
  返回 nil，不伪造空对象。
- `memory-blob-port` 只用于测试与冒烟。

## 6 HTTP 路由

`(gateway/handler gw)` 返回 Ring handler：

| 路由 | 方法 | 语义 |
|---|---|---|
| `/chsk` | GET/POST | Sente 握手、长轮询、Ajax POST |
| `/blobs/:id` | GET/HEAD | 不可变内容下载；支持单段 `Range`、ETag、304 |
| `/blobs` | PUT/POST | 上传；返回 `{:id :size :sha256}`，默认上限 100 MiB |
| `/sync-objects/:token` | GET/HEAD | 大状态对象；`no-store`、无 Range、epoch 限定 |
| `/healthz` | GET | feed 的 epoch、seq、max-pending、订阅数与 stale 数 |
| 其他 | 任意 | 交给 `:fallback`；没有 fallback 时 404 |

Blob 响应：

- `ETag: "<sha256-id>"`，`Cache-Control: public, max-age=31536000, immutable`；
- `Accept-Ranges: bytes`；非法 Range 返回 416 与 `Content-Range: bytes */size`；
- `X-Content-Type-Options: nosniff`；默认 `application/octet-stream`，
  可通过 `:content-type-fn` 覆盖；
- `text/html`、`application/xhtml+xml`、`image/svg+xml` 与 XML 类型额外带
  `Content-Security-Policy: sandbox`，防止把不可信产物直接打开成同源活动
  文档；普通图片、PDF 与二进制不受影响；
- `?filename=...` 只接受 `[A-Za-z0-9._-]{1,128}`，用于
  `Content-Disposition`。

`/chsk` 的 POST/PUT body 限制 1 MiB，表单与二进制请求一视同仁；网关解析
表单参数并安装 `:params`。Sente
固定读取的 `client-id`、`csrf-token`、`handshake?`、`ppstr`、`binary?`、
`udt` 保持 keyword；其他参数名保持字符串，且每次请求最多保留 64 个未知
参数，避免任意查询串无界 intern keyword。畸形百分号转义保留原文，不抛
500。`/blobs` 的 body 不经过该解析。

## 6.1 认证与浏览器边界

默认策略同时要求 **loopback 对端** 和 **同源浏览器请求**：

- `:remote-addr` 必须是严格的 `127.0.0.0/8` 字面量、`::1` 或
  `localhost`；`127.evil.example`、`127.0.0.1.nip.io` 这类仅以 `127.`
  开头的 DNS 名称不是 loopback；
- 若请求带 `Origin` 或 `Referer`，其 origin 必须等于请求自身的可信 Host
  origin，或属于 `:allowed-origins`；
- `Origin: null`、空值、畸形 URL 一律拒绝，不当作“非浏览器请求”；
- 同源匹配还要求 `Host` 是可信本地主机；可信 Host 只接受 `localhost`、
  IPv6 loopback 与严格 127/8 字面量，因此 DNS rebinding 无法用
  `127.evil.example` 伪装成本地同源；
- `Host` 只接受 `host`、`host:port`、`[ipv6]` 或 `[ipv6]:port`；含 `@`、
  多余冒号或其他非法字符的 Host 直接拒绝；
- `:allowed-origins` 与请求 origin 都归一化为小写
  `scheme://host[:port]`，并去掉 http `:80` / https `:443` 默认端口；
- 两者都没有的请求按非浏览器客户端处理：仍受对端检查限制，且 `Host`
  必须是可信本地主机。否则 DNS rebinding 页面可以用无 Origin 的 GET 读取
  loopback 路由。所有会改变状态的路由必须是 POST/PUT/DELETE 等非安全
  方法；GET 路由必须保持只读。

`:allowed-origins :all` 表示“只允许同源”，**不是**通配符。Host 与 Origin
只承担浏览器 CSRF 与 DNS rebinding 检查，**不是认证**：远程客户端可以伪造
两者。因此默认策略只接受 loopback 对端；`:allow-remote? true` 必须同时提供
`:authorized?-fn` 或 `:authorize-request`，否则 `gateway/start!` 以
`:REMOTE_REQUIRES_AUTH` 拒绝启动。自定义钩子一旦提供就完全接管认证；
`:allow-remote?` 只是声明，不再参与默认策略。`hc.server.gateway.auth/wrap`
可包住应用自有路由（附件、工作区文件、语音等），使用同一策略。

应用装配 `hc.server.app.system` 从配置 `:gateway` 读取 `:allowed-origins`、
`:trusted-hosts`、`:max-pending` 与四个大小边界；**EDN 配置拒绝
`:allow-remote? true`**，因为 EDN 无法提供认证钩子。远程部署应让认证反向
代理在本机终止认证，使应用只看到 loopback 对端；代理转发的 Host/Origin
仍需加入 `:trusted-hosts` / `:allowed-origins`，但它们只用于同源判断。
`hc.server.app.config/normalize` 在启动前把 `:allowed-origins` 规范成 set
或 `:all`、把 `:trusted-hosts` 规范成 set、把 `:allow-remote?` 限制为
布尔值；`:gateway` 是封闭 schema，`:authorized?-fn` / `:authorize-request`
等代码钩子不能从 EDN 注入。Sente 1.22 只接受 set 或 `:all`；网关把 Sente
自带的重复 origin 检查固定为 `:all`，因为它在给定 set 时会拒绝没有
Origin/Referer 的非浏览器客户端。真正的策略由 `:authorized?-fn` 调用
`auth/authorize` 执行。

`:host` 绑定到非 loopback 地址时，静态 fallback 仍可被远程读取；网关的
命令/字节路由仍按上述策略 fail-closed。不要把 `:host 0.0.0.0` 当作远程
认证方案；需要远程访问时，让本机认证反向代理连接 loopback。

`:fallback` 路由（静态资源、根页面）不经过网关认证，因为字节与页面属于
应用。应用必须像 `hc.server.app.system` 那样在 fallback 之前用
`auth/wrap` 保护所有命令/字节入口；否则跨源页面可以直接访问这些入口。

Sente 的 `:csrf-token-fn` 默认跳过，因为同源 Origin/Host 检查承担了浏览器
CSRF 边界；若绕过 `auth/authorize`，必须恢复 token 或等价的拒绝函数。
注意这条边界依赖“浏览器对非安全方法发送 Origin”这一事实；因此不得新增
带副作用的 GET 路由，也不得把命令入口放到无 Origin 即可触发的 GET 上。

## 7 Sente 细节

- 默认 packer 是 Transit/JSON。客户端与服务器必须一致。
- 出站 packer 包一层 1 MiB 上限；超限事件在打包阶段被拒绝。`publish!`、
  `broadcast!`、订阅基线与 resync 在改状态前先用 `pr-str` 的 UTF-8 字节
  数估算；字符串转义与映射标点都计入，避免预检低估后 feed 已推进、packer
  才拒绝。状态
  快照/增量超限时转大对象引用（§3.2）；其他事件仍返回
  `:SENTE_MESSAGE_TOO_LARGE`，不推进 epoch/seq。
- 入站事件在分发前估算大小；超过 `:max-sente-message-bytes` 返回
  `:SENTE_MESSAGE_TOO_LARGE`，不触达 Owner。`/chsk` POST 另有 1 MiB body
  限制，二进制 POST 也走同一上限。
- 默认 `user-id-fn` 使用 Sente 的 `client-id`，因此每个浏览器连接有独立
  uid；多个客户端共享同一会话状态，但各有订阅。
- 默认 `csrf-token-fn` 返回 `:sente/skip-CSRF-check`；Sente 自身的
  `:allowed-origins` 由 `:sente-allowed-origins` 控制，默认 `:all`，所以
  真正的 origin/Host 检查由 `:authorized?-fn`（默认调用
  `auth/authorize`）承担。不要给 `:sente-allowed-origins` 传 set：Sente
  的检查会拒绝没有 Origin/Referer 的非浏览器客户端，而且它的 Referer
  前缀比较弱于 `auth/authorize`。面向浏览器的部署可额外注入
  `:csrf-token-fn`、`:reject-fn` 或 `:authorized?-fn`；自定义
  `:authorized?-fn` 必须自行完成全部检查。
- 服务器推送统一包成 `[:hc/event event]`；客户端传输层取出 `event` 后交给
  `hc.client.sync`。入站事件是 `:hc/subscribe`、`:hc/command`、
  `:hc/receipt`、`:hc/ack`、`:hc/resync`。
- `:hc/command`、`:hc/receipt`、`:hc/ack`、`:hc/resync` 的
  `:subscription-id` 可选；携带时必须是当前订阅代号，旧代号返回
  `:STALE_SUBSCRIPTION`，畸形值返回 `:SUBSCRIPTION_ID_REQUIRED`。省略时
  使用该 uid 的当前订阅，保持根 §7.3 的命令 envelope 兼容；该 uid 没有
  当前订阅时返回 `:STALE_SUBSCRIPTION`（`:action :resubscribe`）。
  `:hc/resync` 只检查代号（若给出），不检查 `:stale?`，因此可以治愈
  stale 订阅。
- `:hc/receipt` 找不到回执（nil 或 `{:status :not-found}`）时返回
  `{:ok? true :command-id id :status :not-found :receipt nil
  :action :resync-state}`；不伪造成功。
- 浏览器默认不能提交 `:review/submit`、`:board/command`、`:report/ack`、
  `:report/register`、`:session/issue-epoch`、`:app/issue-epoch` 与全部
  `:fleet/*`；返回 `:COMMAND_KIND_FORBIDDEN`。关键字与同名字符串两种写法
  都会被拒收。应用只有在具备独立角色/来源校验时才能通过
  `:allowed-command-kinds` 放行。
- 网关只校验 `:command-id` 与订阅代号；`:command-epoch` 原样转发（包括
  nil），绝不与 feed 的同步 epoch 比较。
- `stop!` 只关闭网关；关闭标签页或释放订阅绝不取消服务器任务。

## 8 测试

```bash
clojure -M:test:server -n hc.server.gateway.feed-test
clojure -M:test:server -n hc.server.gateway.patch-test
clojure -M:test:server -n hc.server.gateway.diff-test
clojure -M:test:server -n hc.server.gateway.sync-object-test
clojure -M:test:server -n hc.server.gateway-test
clojure -M:test:server -n hc.server.gateway.http-test
clojure -M:test:server -n hc.server.gateway.client-contract-test
clojure -M:test:server -n hc.server.gateway.security-test
```

`http-test` 会启动真实的 JDK HTTP 服务器，通过 `java.net.http.HttpClient`
完成 Sente Ajax 握手、订阅、快照、增量、命令回执、stale 订阅拒绝，以及
blob 上传/下载/HEAD/Range/304/404；它还验证 `:app/issue-epoch`（关键字与
字符串两种写法）在到达 Owner 之前返回 `:COMMAND_KIND_FORBIDDEN`。它不使用
模拟 socket。
`gateway-test` 还覆盖 `stop!` 在 Sente 接收循环未排空时返回
`:stopped? false`，并在再次调用时重新观察排空。

`client-contract-test` 只做跨层合同检查：把网关事件直接喂给
`hc.client.sync`，验证基线、增量、`MESSAGES_SNAPSHOT`、新纪元替换与旧订阅
丢弃；它还验证大快照引用经摘要校验后完成客户端基线。服务端源码不
require 客户端包。

`security-test` 覆盖严格 loopback、Host 语法与 DNS rebinding（`127.evil.example`、
`127.0.0.1.nip.io`、`localhost.evil.example`、`127.0.0.1:7575@evil` 均拒绝）、
默认端口归一、
远程模式无认证钩子时拒绝（`:REMOTE_REQUIRES_AUTH`）、同源/Origin null/畸形
Origin、403 中间件、
浏览器内部命令拒绝（含 `:app/issue-epoch` 与字符串伪装）、缺失订阅代号、
`:not-found` 回执、epoch 原样转发、重复与退休 epoch、stale 后 resync 治愈、
活动 blob 的 `CSP: sandbox`、未知查询参数保持字符串，以及 1 MiB 入站/出站
与 packer 边界。

服务端 RFC 6902 applier 与 `hc.client.sync.json-patch` 语义一致，但刻意
分属两个包：`hc.server` 不依赖浏览器客户端。修改任一侧的指针、数组或
失败原子性语义时，两侧测试都要改。

## 9 非目标

- 不拥有生产 HTTP 服务器、静态资源、根页面或路由。
- 不执行命令，不做命令去重，不决定任务/看板状态。
- 不删除 blob，不维护引用表；引用与清理属于存储适配器。
- 不把大对象、日志或附件放进 Sente 状态树；大状态快照/增量只以
  `/sync-objects/<token>` 引用出现在事件中。
- 不实现浏览器端 Fulcro/Framework7 视图或 `hc.client.sync` 的归约。
