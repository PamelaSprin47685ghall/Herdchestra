# hc.client.backup — 加密备份与恢复界面

> 权威顺序：根 `AGENTS.md` §7.1–§7.4 旅程 7（导出、备份与迁移）与
> §7.4 旅程 8.5（排障与未交付理由） > `src/AGENTS.md` >
> `src/hc/client/AGENTS.md` > 本文件。服务端合同见
> `src/hc/server/backup/AGENTS.md` 与 `hc.server.backup.service/handle!`。

## 1 责任

1. 浏览器端备份工作区：目标选择、类别选择、密码与加密、创建备份、校验、
   恢复预览、合并/替换冲突选择、本地恢复点与 7+4+3 清理。
2. 只通过 Sente 命令通道提交 `:backup/*` payload；服务端回执是唯一完成
   依据。点击、预览或本地表单校验都不宣布备份或恢复成功。
3. 密码只保留在页面内存；密码不写 `localStorage`，不写日志，不在成功后继续
   保留。服务端的最小长度规则按 Unicode 码点计数，支持中文、空格与 emoji；
   首尾空格有意义，绝不 trim。`bridge.cljs` 只持久化非密钥选项
   （加密开关、类别、包含选项、分区与恢复方式），绝不持久化密码、确认框或
   危险确认。创建或校验成功后清空 `:ui` 密码，并从 `:commands` 的每个
   `:payload` 删除 `:password`/`:password-confirm`（标记
   `:password-redacted?`）；成功回执归约也执行同一删除。密码显示切换只改
   `:password-visible?` 状态；“复制密码”是显式按钮，由 bridge 在用户点击后
   调用剪贴板，失败不得宣称已复制。
4. 在线恢复永久需要离线维护窗口。`online-restore?` 恒为 false；即使服务器
   快照声明 `:online? true`，`restore-validation` 也始终包含
   `BACKUP_RESTORE_REQUIRES_QUIESCENCE`。界面展示预览、备份 ID、目标与离线
   步骤，不渲染“执行恢复”或“从该恢复点回退”；浏览器从不发送
   `:quiescent?`。
5. 诚实失败：错误码先映射到 24 类内部错误码，再按根 §7.4 旅程 8.5 的十类
   「未交付理由」呈现；未知错误保留服务器消息，不翻译成“网络错误”，
   不把读取失败当作空列表。

## 2 文件

| 文件 | 责任 |
|---|---|
| `model.cljc` | 纯状态、表单校验、错误合同、回执归约、目标/预览/恢复投影 |
| `commands.cljc` | 命令 outbox、命令去重、回执与 `:outcome-unknown` |
| `view.cljc` | 纯 hiccup 树与 scoped CSS；JVM 可检查全部文案 |
| `react.cljs` | React 适配器；密码字段使用 `DraftInput`，DOM 持有实时文本/光标/选区/IME，状态在每次 input 后同步；blur/卸载时提交尚未同步的 DOM 文本；不保存密码到组件本地状态 |
| `bridge.cljs` | `:ui/backup` 本地切片提交、Sente 提交、回执监听、首次状态读取、非密钥偏好持久化 |
| `ui.cljs` | 公共 `backup-element`、`install!`、`sync!` |

## 3 命令合同

信封：`{:command-id string :command-epoch int :payload {:kind :backup/* ...}}`。
命令仅在 `:synced` 且快照 `:sync :command-epoch` 为正时排队；feed 的
`:epoch` 只描述事件流，绝不单独授权命令。同一逻辑目标已有待确认命令时拒绝
重复提交。

| kind | 关键 payload |
|---|---|
| `:backup/status` | `:target-id` 可选 |
| `:backup/create` | `:target-id`, `:categories`, `:password`, `:password-confirm`, `:encrypt?`, `:include-secrets?`, `:confirm-plaintext-secrets?`, `:include-deleted?`, `:include-temp?`, `:include-cache?`, `:include-external-files?` |
| `:backup/list` | `:target-id` 可选 |
| `:backup/preview` | `:target-id`, `:backup-id`, `:password`, `:mode :merge/:replace`, `:categories`, `:resolutions`, `:confirm-memory-reactivation?` |
| `:backup/verify` | `:target-id`, `:backup-id`, `:password` |
| `:backup/delete` | `:target-id`, `:backup-id`, `:confirm? true` |
| `:backup/recovery-points` | 无 |
| `:backup/prune` | 无 |
| `:backup/restore` / `:backup/restore-recovery-point` | 服务端在线拒绝；界面不发送 |
| `:backup/maintenance-restore` | 仅当 `:maintenance :available?` 为真；预览 payload + `:maintenance-id` + `:confirm? true` + `:preview-fingerprint`（预览回执提供时） |
| `:backup/maintenance-status` | `{:maintenance-id ...}`；重连后核对 |
| `:backup/maintenance-restore-recovery-point` | 仅 supervised；`{:recovery-point-id ... :maintenance-id ... :confirm? true}` |

`acknowledge` 不是 Sente 命令：失败确认只通过离线 CLI 合同执行，界面显示
准确的 `status` / `acknowledge` 命令、确认代码与启动门槛，不伪造浏览器端确认。

命令去重键是 `[kind target-id backup-id-or-recovery-point-id-or-global]`；
不同目标可以同时各有一条待确认命令，同一目标不重复排队。创建按钮按
`:encrypt?` 显示“生成加密备份”或“生成未加密备份”。

回执形状：`CUSTOM :hc/command-receipt`，`{:ok? bool :receipt ... :error ...}`。
`preview` 的权威字段：`:mode`、`:summary`、`:conflicts`、
`:placeholders`、`:missing-relations`、`:relink-required`、`:needs-review`、
`:needs-reconfiguration`、`:memory-reactivation`、`:expired-skipped`、
`:permissions`、`:recovery-point`、`:no-changes?`、`:already-imported?`、
`:replace-all?`、`:incomplete-attachments`、`:manifest`。预览视图投影
`:permissions` 与 `:manifest`，并渲染 `:needs-reconfiguration`；恢复结果
渲染 `:warnings` 与 `:permissions` 的实际条目。冲突默认 `:keep-both`；
选项为 `:keep-local`、`:use-backup`、`:keep-both`。

## 4 目标与恢复安全

- 目标来自 `:backup/status` 的 `:targets`/`:default-target-id`/`:target`。
  只显示服务器实际配置的本地文件、WebDAV 或 S3；不可用目标显示原因，不伪造
  远程成功。
- 预览不写资料。替换前由服务端建立本地恢复点；建立失败时恢复不开始。
- `:restore {:online? false :offline? true :requires-quiescence? true}` 是
  永久在线边界：`online-restore?` 恒为 false，服务端快照的
  `:restore :online? true` 不会重新启用按钮。界面显示真实离线入口
  `hc.server.backup.cli restore` / `restore-recovery-point`，密码只从
  `HC_BACKUP_PASSWORD` 或 `--password-file` 读取；不把预览当成已恢复。
  服务端还提供 `hc.server.backup.maintenance/run!` 协调停止 Owner、恢复与
  重启；界面只展示离线路径，不渲染在线“执行恢复/从该恢复点回退”，不伪造
  在线成功。离线 CLI 使用本地 `--target-root`，不能直接读取 WebDAV/S3
  目标。
- 离线协调结果可能是 `:complete`、`:stop-failed`、`:failed`、
  `:restart-failed`、`:failed-needs-attention`、`:failed-needs-recovery`、
  `:failed-commit-unproven`、`:failed-before-commit` 或 `:cancelled`。
  除成功状态外都不得显示为成功；失败状态必须说明已知的提交证据。
- 三种证据状态不得混同：`:failed-needs-recovery` 表示存储可能已提交，
  需要操作员对照恢复点核对；`:failed-commit-unproven` 表示提交结果未知；
  `:failed-before-commit` 表示已确认未提交，资料保持原样且不阻塞下次启动。
  `:failed` 与 `:failed-needs-attention` 没有足够的提交证据。界面不得把
  `:failed-before-commit` 说成成功，也不得把未知提交说成未提交。
- 阻塞失败（`:failed`、`:failed-needs-attention`、`:failed-needs-recovery`、
  `:failed-commit-unproven`）在 `:acknowledged?` 为真前保持启动阻塞。
  `:failed` 且 `:restore-attempted? false`、没有 `:needs-attention?` /
  `:needs-recovery?` / `:commit-unproven?` / `:storage-touched?` 标志时，
  是恢复未开始的记录，不阻塞。
  界面显示后端 `acknowledge` 的离线合同：先运行 `status` 读取
  `state-digest`/`restore-digest`，再运行 `acknowledge`；确认代码按证据选择
  `inspect-and-accept-partial` 或 `inspect-accept-unknown-commit`，恢复另一份
  备份后用 `restore-another-backup`。确认命令必须带 `--db-path` 与
  `--store-changed true`（操作员已确认当前存储不再匹配失败证据）；未带该
  断言或存储仍匹配时服务器拒绝确认。`:acknowledged?` 只表示操作员比较过，
  不把失败改成成功。`:failed-before-commit` 不可确认，也不需要确认。
- `:backup/status` 的 `:maintenance` 是工作台停机恢复能力声明。
  只有 `:available? true` 且 `:mode :supervised` 才视为可用；其他组合按
  offline-only 处理。
  offline-only 服务端声明
  `{:available? false :mode :offline-only :reason :NO_IN_PROCESS_QUIESCE
  :command "pixi run backup"}`，界面显示不可用原因和离线命令，不显示伪造的
  “停机并恢复”按钮。supervised 服务端声明
  `{:available? true :mode :supervised :active <status> :last <status>
  :quiesce {...} :restart {...} :reconnect {...}}`；界面使用
  `:backup/maintenance-restore` / `:backup/maintenance-status`。HTTP/Sente 在
  恢复期间会主动断开，服务端不保证进度 CUSTOM 事件；重连后必须以
  `:backup/maintenance-status` 回执为唯一进度/终态依据。网关按定时器重发
  完整状态快照，因此 bridge 只在维护活动时做有界自动核对：同一维护 ID 的
  状态、`:acknowledged?` 与证据字段未变且距上次核对不足 2 秒时不再发送，
  已有待确认 `maintenance-status` 时不重复排队；显式“核对维护状态”仍立即
  提交。`:accepted` /
  `:validating` / `:stopping` / `:restoring` / `:restarting` 显示进行中；
  `:restart-failed` / `:failed-needs-attention` / `:failed-needs-recovery` /
  `:failed-commit-unproven` / `:failed-before-commit` / `:stop-failed` /
  `:failed` 显示失败与已知证据；终态失败停止自动核对，操作员可用
  “核对维护状态”重新读取。`:complete` 携带 `:no-changes?` 或
  `:already-imported?` 时只显示“没有写入”信息，状态 badge、状态事实与底部
  说明都不显示“恢复完成”，不冒充新的恢复。这些维护状态是操作回执，
  不是用户侧交付状态；会话交付仍只用进行中/已交付/未交付。
- 恢复点保留最近 7 个成功点、4 周与 3 月代表副本；清理只删除策略外点。
  恢复点行投影 `:bytes` 并显示人类可读大小。维护或恢复进行中时，
  “按 7+4+3 清理”按钮禁用。
- 连续导入同一备份由服务端按 provenance/摘要幂等跳过；界面显示
  “此前已导入，本次跳过”。
- 备份只包含部分类别时，预览返回 `BACKUP_CATEGORY_MISSING`；界面自动把
  恢复类别收窄到服务端报告的 `:available` 集合、标记预览陈旧并提示重新
  预览，绝不静默假装缺失类别会被恢复。切换恢复类别、全选/全不选或收窄后
  都设置 `:preview-stale?`；`restore-validation` 还比较预览回执的
  `:categories` 与当前选择，缺失该字段时退回不比较。
- 创建回执的最近备份显示文件位置、大小、创建时间与
  `:verification :error`；大小来自目标元数据 `:size`，位置来自 `:path`。
- 工作区类别包含受管文件；外部工作区默认只恢复配置并要求重新定位/授权。
  显式包含外部文件时，服务端只恢复应用自有导入，不创建原路径，也不自动
  授予写权限。

## 5 挂载

工作台 `views/settings.cljs` 只在设置分区 `:data`（数据中心）渲染备份面板：

```clojure
(when (data-section? db)
  (diagnostics/diagnostics-element db))
(when (data-section? db)
  (backup-ui/backup-element db))
```

供应商、模型与外观分区不挂载备份或诊断面板，也不显示备份密码输入框。
挂载条件只读取 `:ui/settings :ui :section`；服务器投影保留当前分区，不会
因刷新把面板切走。

`main.cljs` 在 Fulcro app 启动后调用一次：

```clojure
(backup-bridge/install! app)
```

`install!` 注册回执监听并在首次 `:synced` 且无状态时自动读取一次
`:backup/status`。`backup-element` 从 `:ui/backup` 读取状态；密码只在该
内存 map 中。组件不依赖 Framework7，可在 Node 中 server-render。

卸载面板不会取消已运行备份：命令记录、outbox 与回执监听属于
`bridge.cljs`/`main.cljs`，不属于 React 组件。回执在面板隐藏时仍写入
`:ui/backup`；重新进入数据中心后显示同一命令的终态，不重发命令。

### 5.1 密码输入生命周期

备份密码、确认密码与恢复密码是**非受控草稿输入**（`view.cljc` 的
`password-input`，`react.cljs` 的 `DraftInput`）。原因不是样式偏好：bridge
把 `:ui/backup` 直接写回 Fulcro 状态原子，再用 `schedule-render!` 请求
防抖渲染；如果输入同时用 `:value` 受控，React 会在两次快速按键之间把 DOM
恢复到上一次提交的旧值，下一次 `event.target.value` 便只剩新字符。真实
浏览器中输入 `abcdef123456` 只剩 `6` 即由此产生。

不变量：

1. DOM 是可见文本、光标、选区和 IME 组合态的唯一所有者；每次 `input` 与
   `compositionend` 把完整值同步到 `:ui/backup`，创建/校验/预览读取该状态。
2. 普通重渲染、服务器快照、其他切片更新与维护事件不得写回密码 DOM。只有
   `:ui :password-reset` 变化（显式“清除密码”或成功回执）才允许写回空值；
   该 token 不持久化。
3. 普通渲染从不回写 DOM，即使输入框聚焦或正在组合；组合结束仍派发一次
   最终值，避免 `compositionend` 早于最后一个 `input` 的浏览器截掉末字。
4. 切换分区或重新挂载时用当前状态作为 `defaultValue` 种子，内存中的草稿
   仍在；不得把密码写入 localStorage、日志或服务端回执。
5. 离开数据中心会卸载面板。`DraftInput` 在 `blur` 与卸载清理时比较 DOM
   值与最后已同步值：若 DOM 领先（例如 IME 组合尚未结束、最后一个 `input`
   尚未到达），把 DOM 文本写回 `:ui/backup` 后再卸载。若 `:password-reset`
   已变化但重置副作用尚未执行，则以状态中的空值为准，绝不用旧 DOM 文本
   复活已清除的密码。
6. “显示密码/隐藏密码”只切换 `:password-visible?` 并改变输入框的 `type`，
   不读写 DOM 值、不改变 `:ui` 密码。“复制密码”只由显式点击产生
   `:backup/copy-text` effect；bridge 调用剪贴板后用 `:backup/copy-result`
   更新提示。剪贴板不可用或拒绝时把这次复制作为操作回执显示失败，
   不宣称已复制；该回执不是会话交付状态，密码不进入日志或本地存储。

回执可能比命令记录先到，也可能在 sync-bridge 自己的事务/渲染过程中到达。
因此 bridge 先把命令记录写入 Fulcro，再发送 outbox；回执监听只处理已知
命令，并在下一任务中把 `:ui/backup` 直接写回 Fulcro 状态原子后请求重绘。
它不触碰服务器实体表或其他 `:ui/*` 切片。

维护失败后界面显示“重新连接并核对”，发送纯动作 `:backup/reconnect`。
bridge 对它调用无参的 `hc.client.workbench.sync-bridge/reconnect!`，不传
原因参数，也不直接改适配器或 `:ui/*`。`reconnect!` 的正结果只表示已请求
Sente 重连，不是“已同步”；bridge 随后按当前 `:ui/connection` 重新投影（失败
时投影为 `:interrupted`）并提交 `:ui/backup`。新的 Sente 状态到达后仍由
`main.cljs` 的 watch 再投影一次。维护终态只以 `:backup/maintenance-status`
回执为准，重连本身不宣布恢复完成。

## 6 测试

```bash
clojure -M:test -n hc.client.backup.model-test
clojure -M:test -n hc.client.backup.bridge-test
clojure -M:test -n hc.client.backup.password-input-test
test/js/verify_backup_password_dom.sh
```

覆盖：码点密码规则、类别独立选择、创建 payload、创建/校验成功后密码清除与
命令 payload 删除密钥、密码显示切换与复制 effect、明文密钥确认、命令门槛、
不同目标不互相阻塞、目标选择、预览冲突与 stale、恢复类别切换/全选/收窄与
预览 `:categories` 比较、no-changes/already-imported、离线恢复拒绝（服务端
声明 `:online? true` 也不排队）、恢复结果、三种失败证据状态（已提交需核对、
结果未知、确认未提交）、确认代码与启动门槛、确认后的非成功文案、错误文案、
删除确认、预览 permissions/manifest/needs-reconfiguration、恢复 warnings 与
permissions 条目、创建结果的位置/大小/校验错误、恢复点大小与清理禁用、
维护 no-write 文案、supervised 门槛、纯树文案；
`bridge-test` 另检查连接投影
（中断不得投影成 `:synced`、缺少连接投影时为 `:disconnected`）与“重新连接并核对”
只在维护未终结且未同步时出现。`password-input-test` 检查密码字段的 draft
标记、初始 reset token 与清除后的 token；`verify_backup_password_dom.sh`
在 jsdom 中挂载真实 React 适配器，在**两次按键之间不渲染**的条件下输入
`abcdef123456`，并覆盖 IME 组合、聚焦时的服务器重渲染、光标/选区保持与
显式清除。同一 DOM 回归还挂载真实工作台 `settings-page`：供应商、模型与
外观分区不出现 `.hc-backup`/`.hc-diagnostics`；在数据中心输入密码后把 DOM
推进到状态之前（模拟未结束的 IME），切到模型分区会先提交草稿再卸载，切回
数据中心后输入框仍显示该草稿；面板隐藏期间应用 `:backup/apply-receipt`
后，命令变为 `:applied`，重新进入数据中心显示“最近一次备份”而不是待回执
提示。需要真实 Sente 的重连时序仍由浏览器冒烟脚本覆盖。

`DraftInput` 的 `inputAttrs`、`resetKey`、`onInput`、`onCompositionEnd`
是自定义 React props，不是 DOM/React externs。Closure advanced 会重命名
`.-inputAttrs` 之类的点访问，但不会重命名 `#js` 字面量里的字符串键；因此
适配器必须用 `(aget props "inputAttrs")` 等字符串访问。jsdom 测试使用
`:none` 优化，抓不到这个问题；发布前必须用 `shadow-cljs release :app`
构建并打开 `#/settings` 备份面板作为 release 路径检查。

`verify_backup_password_dom.sh` 编译时只把 `test/js/*_shim.js` 列为
`:foreign-libs`。工作台设置页引入的
`com.fulcrologic.fulcro.application` 需要 `react-dom/client`；
`test/js/react_dom_client_shim.js` 提供该命名空间，
`run_backup_password_dom_tests.cjs` 在加载编译结果前注入真实的
`node_modules/react-dom/client.js`。不要把 foreign-lib 指向
`node_modules/...`：Closure 会把包内 `package.json` 当 JS 模块写进输出树，
Node 解析包时抛 `ERR_INVALID_PACKAGE_CONFIG`。脚本每次运行创建独立的
`out-backup-password-dom.XXXXXX`，成功后才删除；失败时保留路径，绝不删除
共享的 `out-backup-password-dom` 或其他运行产物。断言仍挂载真实
`react-dom/client` 根、真实 `react-dom/server` 标记与真实 Fulcro 状态，
不以源码文本或常量自证代替。失败时
`:end-run-tests` 的 `[:cljs.test/default :end-run-tests]` 分支把
`process.exitCode` 置 1，脚本 cleanup trap 原样退出该状态，不会出现
“有失败但 exit 0”。
工作台 `settings-page` 的 Fulcro DB 键是顶层 `:ui/backup`；备份适配器
内部状态才在该 map 的 `:ui` 下。测试断言应写 `[:ui/backup ...]` 或
`[:ui/backup :ui ...]`，不得写成 `[:ui :backup ...]`。

真实 React server-render：

```bash
rm -rf target/backup-ui
clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M:cljs:client \
  -co '{:target :nodejs
        :output-to "target/backup-ui/main.js"
        :output-dir "target/backup-ui"
        :node-modules-dirs ["node_modules"]
        :optimizations :none}' \
  -c hc.client.backup.ui-smoke
printf '{"type":"commonjs"}\n' > target/backup-ui/package.json
node -e "require('./target/backup-ui/main.js'); hc.client.backup.ui_smoke._main();"
```

独立浏览器冒烟（不需要服务器；验证真实 DOM 挂载）：

```bash
rm -rf target/backup-browser
clojure -Sdeps '{:paths ["src" "resources" "test"]}' -M:cljs:client \
  -co '{:target :browser
        :output-to "target/backup-browser/main.js"
        :output-dir "target/backup-browser"
        :node-modules-dirs ["node_modules"]
        :optimizations :none}' \
  -c hc.client.backup.browser-smoke
# 修正生成 main.js 中的相对脚本路径后，用任意静态服务器提供仓库根目录，
# 打开 target/backup-browser/index.html 并检查
# 加密备份与恢复 / 在线恢复不可用 / 保留本地版本 / 无法连接 WebDAV。
```

真实浏览器 + Sente + 真实备份服务的往返：

```bash
node test/hc/client/backup/run_sente_roundtrip.cjs
```

脚本用临时存储启动**真实网关 + 真实备份服务 + 真实存储 + 真实静态客户端**
（`sente_roundtrip_server.clj`），经 CDP 打开真实 `#/settings` 客户端，等待
五阶段同步后点击“数据中心”挂载备份面板，再通过
`hc.client.backup.bridge/dispatch!` 发送
`:backup/status`、`:backup/create`、`:backup/preview` 等命令，断言回执、
错误密码不改变列表、缺失类别收窄、预览字段、离线恢复提示与 CLI，并额外
发送一条原始 `:backup/restore` 命令确认服务器回执为
`BACKUP_RESTORE_REQUIRES_QUIESCENCE`。还检查 DOM 无障碍事实：区域/单选组
标签、密码输入标签、按钮可读名称、禁用在线恢复、44px 热区与横向溢出。
需要 `google-chrome` 与 `ws` npm 包。

该脚本故意不启动完整 `hc.server.app.system`，避免其他包的在途编辑阻断
备份传输审计；生产命令路径仍是 Sente -> OwnerPort -> 真实 backup service。
完整 app 的 `#/settings` 冒烟另由 workbench 执行。

supervised 停机/恢复/重连（真实进程级 supervisor）：

```bash
node test/hc/client/backup/run_sente_supervised_roundtrip.cjs
```

脚本用固定端口启动完整 `hc.server.app`，创建/预览备份，发送
`:backup/maintenance-restore`，等待 HTTP/Sente 主动断开，重连后以
`:backup/maintenance-status` 回执为唯一终态依据，并断言
`:complete` + `:observed-by-client? true`。该脚本通过 CLJS 全局变量驱动，
需要 dev `:app` bundle（release/advanced 优化不暴露 `cljs`/`hc` 全局）。
共享服务器编译损坏或 supervisor 未接线时脚本失败，不得记为通过。

2026-11-03/04 实测：备份 UI JVM `30 tests / 229 assertions` 全通过；完整
`:compose:server:client` 套件 `2575 tests / 16990 assertions`、0 failures /
0 errors（运行期间其他包仍在编辑，start/end tree hash 不同）。Node SSR
`BACKUP UI SMOKE OK`；真实浏览器 + Sente 离线往返 `BACKUP SENTE ROUNDTRIP OK`
（status/create/错误密码/缺失类别收窄/preview/离线 CLI/原始 restore 拒绝；
a11y：region、2 个有标签密码框、radiogroup、无未命名按钮、44px 热区、
无横向溢出）；完整 `#/settings` 进入“数据中心”后显示五个备份分区且无启动
错误；
supervised 真实进程级往返 `BACKUP SUPERVISED ROUNDTRIP OK`：完整 app
创建/预览 → maintenance-restore → HTTP/Sente 主动断开 → 重连 →
maintenance-status 终态 `:complete` 且 `:observed-by-client? true`。
未运行：真实 WebDAV/S3 目标、真实离线 CLI/维护协调恢复、屏幕阅读器。

生命周期边界：普通 live `:backup/restore` 仍被服务端拒绝。只有
`:maintenance :available? true` 且模式为 `:supervised` 时，工作台才显示
“停机并恢复”；offline-only 状态显示不可用原因与离线 CLI。supervised 路径
依赖服务端 quiesce/supervisor 在 HTTP/Sente 断开后继续恢复，并通过重连后的
`:backup/maintenance-status` 回执报告终态；不得从断线或点击推断成功。
真实 supervised 往返已验证连接断开、重连与 `:observed-by-client? true`。

## 7 非目标

- 不实现服务端备份格式、加密、目标传输、数据库恢复或 quiescence 判定。
- 不编辑 `hc.server.backup`、工作台外壳或设置内部实现。
- 不把恢复预览、按钮点击、HTTP 200 或本地校验当成领域成功。
