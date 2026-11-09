# app-check 0.0.3 evidence

- 命令：

```text
timeout -k 10 300 .pixi/envs/default/bin/clojure -M:server -m hc.server.app --check --port 0
```

- 退出码：`1`

## Health 原始输出

以下为 app-check stdout 的 `pr-str` health map，原样保留，未重排：

```clojure
{:retention-gc {:session-last-error nil, :running? true, :blob-interval-ms 86400000, :last-run-at nil, :terminated? false, :session-runs 0, :session-last-result nil, :blob-days 7, :blob-last-result nil, :blob-last-error nil, :blob-runs 0, :session-interval-ms 86400000, :session-days 30, :stopped? false}, :git-dirty? true, :owners {"hc-session-2c94dfe3-cadf-40d2-8444-f9eb55e3bbd0" {:version 3, :conversation 0, :pending-turns 0, :calls {}, :turn? false}}, :store {:open? true, :path "/tmp/hc-check-11349574114270178908/data/db"}, :config {:host "127.0.0.1", :port 0, :workspace-root "/tmp/hc-check-11349574114270178908/work", :data-dir "/tmp/hc-check-11349574114270178908/data", :log-level :info}, :browser {"hc-session-2c94dfe3-cadf-40d2-8444-f9eb55e3bbd0" {:permission {:enabled? false, :scope :current-tab, :sites [], :private-read-approved? false, :model-provider nil, :persisted? false}, :connected? false, :tabs {:total 0, :open 0, :closed 0}, :configured? false, :offline? true, :cards {:count 0, :dropped 0, :truncated? false}, :takeover {}, :limits {:coordinates :resolved-against-current-dom, :external-browser :not-controlled, :permission :ephemeral-not-inherited, :outcome-unknown :no-auto-retry, :observation :top-document-visible-text, :iframes :not-read, :page-content :may-contain-personal-data, :sensitive-detection :best-effort, :operator-input-uncovered :fail-closed, :operator-input :isolated-world-top-document-only, :screenshot :historical-not-live}, :stopped? false, :operator-input {:detection :unavailable, :uncovered-tabs 0}}}, :gateway {:epoch 1, :seq 1, :max-pending 4096, :subscriptions 0, :stale 0}, :models {:configured? false}, :components {:retention-gc {:session-last-error nil, :running? true, :blob-interval-ms 86400000, :last-run-at nil, :configured? true, :terminated? false, :started? true, :session-runs 0, :session-last-result nil, :status :started, :blob-days 7, :blob-last-result nil, :blob-last-error nil, :blob-runs 0, :session-interval-ms 86400000, :session-days 30, :stopped? false}, :scheduler {:started? true}, :backup {:started? true}, :diagnostics {:started? true}, :fleet-gc {:configured? true, :started? true, :status :started}, :model-auth {:started? true}, :search {:started? true}, :fleet {:configured? true, :available? true, :status :started}, :extensions {:started? true}, :materials {:started? true}, :speech {:started? true}, :steward {:configured? true, :started? true, :status :started, :last-tick-at nil, :persist-conflicts 0, :last-error nil}, :artifacts {:started? true}, :model {:configured? true}}, :ok true, :history-index {:state :ready, :generation 1, :session-count 1, :message-count 0, :built-at 1794186990310, :needs-rebuild? false, :reason nil}, :publish-error nil, :component-errors {}, :sessions {:count 1}, :version "0.0.3", :git-hash "1623d4e4c5f6", :uptime-ms 15, :steward {:configured? true, :started? true, :status :started, :last-tick-at nil, :persist-conflicts 0, :last-error nil}}
```

逐项核验（值直接取自上面的原始 map）：

- `:ok` = `true`
- `:version` = `"0.0.3"`
- `:git-hash` = `"1623d4e4c5f6"`（不是 `"unknown"`）
- `:git-dirty?` = `true`（布尔值存在）
- `:component-errors` = `{}`
- `:sessions` = `{:count 1}`
- `:retention-gc` 已装配：顶层 `:running? true`；`:components :retention-gc` 为 `:started? true, :status :started`；`session-days 30`、`blob-days 7`
- `:history-index` 已装配：`:state :ready, :generation 1, :session-count 1, :needs-rebuild? false`

## Stop 证据与退出

app-check 随后打印：

```clojure
{:error :STOP_NOT_CONFIRMED, :stop {:stopped? false, :drained? true, :store-closed? false, :failures []}}
```

- `:failures` 为空，但 `:stopped? false` 且 `:store-closed? false`；退出码为 `1`。
- 这是 `system.clj` 的终止判定缺陷：health 通过，但 stop 证据未确认，store 没有关闭。
- 正常退出未留下 `/tmp/hc-check-*` 的回归也因此未成立。

## 判定

**BLOCKED，不能升为 PASS。** 两个独立原因：

1. **stop 证据失败**：`--check` 退出码 1，`:STOP_NOT_CONFIRMED`，`:stopped? false` / `:store-closed? false`。health 全绿不足以抵消。
2. **并发编辑回归**：本次 app-check 之后，当前 worktree 的 `src/hc/server/app/system.clj:5725:58` 报 `Syntax error reading source ... Unmatched delimiter: ]`；新的 `clojure -M:server` 在启动前即失败，因此真实启动、HTTP `/healthz`、SIGTERM 优雅退出、`initialized.edn`/库文件断言均无法执行。上面的 app-check 输出对应语法错误出现前的树。

即使语法错误修好，**仍需修好 stop 证据**，再重跑完整 F1 验收，才能升级。

## 正面事实

- `:retention-gc` 已在 app-check 中实际装配并运行（`:started? true`、`:status :started`），解除“未装配”疑点。
- `:history-index` 已装配且 `:state :ready`，解除“未装配”疑点。

## 启动身份

实际运行时的 identity 值为：

```text
Herdchestra 0.0.3 git=1623d4e4c5f6 dirty=true
```

该行与 app-check health 一致；由于后续 `system.clj` 语法错误阻断启动，本轮未从启动 stdout 抓到该行。

## 清理记录

- 本次 app-check 的临时目录 `/tmp/hc-check-11349574114270178908` 因 store 未关闭而残留，已手动删除。
- `/tmp/hc-check-11651963775718753785` 非本 worker 创建，未动。
- 诊断/探针临时脚本已删除。

## 复验前置条件

`system.clj` owner 修复后，必须至少满足：

1. `--check --port 0` 退出码 0；
2. `:stopped? true`、`:store-closed? true`，且正常退出不残留 `/tmp/hc-check-*`；
3. 真实启动 `/healthz` HTTP 200 且字段完整；
4. SIGTERM 退出码 0 或 143，无 `OutOfMemoryError`/`hs_err`；
5. `<data-dir>/data` 库文件与 `initialized.edn` 存在；
6. 启动 stdout 含 `git=` 与 `dirty=`。

## F1 复验：PASS（2026-11-09）

上述 BLOCKED 记录保留为历史。以下为 `system.clj` 修复后的正式复验。

### ① app-check

- 命令：`timeout -k 10 300 .pixi/envs/default/bin/clojure -M:server -m hc.server.app --check --port 0`
- 实际：`EXIT=0`
- health：`:ok true`、`:version "0.0.3"`、`:git-hash "1623d4e4c5f6"`、`:git-dirty? true`、`:component-errors {}`、`:sessions {:count 1}`；`:retention-gc` 与 `:history-index {:state :ready :generation 1 :session-count 1}` 均存在。
- 判定：**PASS**

### ② 真实启动 + `/healthz`

- 命令：`hc.server.app --data-dir <temp>/data --workspace-root <temp>/work --port 0`
- HTTP：`200`；实际响应 `Content-Type: application/edn; charset=utf-8`（服务端健康契约是 EDN，不是 JSON；以下按 EDN 解析）
- EDN 字段：`:ok true`、`:version "0.0.3"`、`:git-hash "1623d4e4c5f6"`、`:git-dirty? true`、`:sessions {:count 1}`（整数）、`:retention-gc` 存在、`:history-index` 存在、`:component-errors {}`
- 启动 stdout 身份行：`Herdchestra 0.0.3 git=1623d4e4c5f6 dirty=true`
- `<data-dir>/initialized.edn`：存在；`<data-dir>` 下库文件共 11 个，含 `data/db/data.mdb`、`lock.mdb`、`store.edn`、`store.lock`、`write-protocol.lock`、`VERSION`
- 判定：**PASS**（字段完整；格式为 EDN）

### ③ SIGTERM 优雅退出

- 实际退出码：`143`
- 最后 20 行 stdout：编译提示、身份行、`Herdchestra listening on http://127.0.0.1:36331/`、workspace、store；无 stop 失败输出
- `OutOfMemoryError`：0；`hs_err`/`.hprof`：0；`STOP_NOT_CONFIRMED`：0
- `/tmp/hc-check-*` 临时目录：0（修复后新 run 未残留；此前 pre-fix 的 `/tmp/hc-check-11651963775718753785` 已清理）
- 判定：**PASS**

### ④ 负向复验

**A. initialized.edn 存在、库目录缺失**

- 第一次 `--check`：exit 0，`initialized.edn` 与 `data/db` 均存在
- 删除 `data/db` 后第二次 `--check`：exit 1
- 错误码：`:STORE_MISSING_AFTER_INIT`
- `data/db` 是否被重建：否
- 判定：**PASS**

**B. data 目录只读**

- `chmod 0555 <data>` 后 `--check`：exit 1
- 错误：`:START_FAILED`，message 为 `cannot create directory <data>/blobs`（指向不可写目录）
- `data/db` 是否创建：否
- 判定：**PASS**（实际错误码 `:START_FAILED`，非存储专用码；消息明确指向不可写路径）

### 最终判定

**F1 可从 BLOCKED 升为 PASS。**
