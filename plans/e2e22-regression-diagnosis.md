# E2E 22 回归失败诊断（0.0.1）

- 日期：2026-11-05
- 范围：E2E 22 B 启动失败；只读诊断，未修改源码。
- 关联报告：`plans/e2e-regression-01-02-22.md`
- 证据：`plans/evidence/e2e-regression-01-02-22/`

## 1. 结论

1. **原始 `ClassCastException: Character cannot be cast to Map$Entry` 已由 W4 修复**：触发点是 `system/clean-model-section` / `without-nil-values` 对持久化 `:models` map 的处理；修复后 B 启动不再抛该异常。
2. **B 启动已恢复健康**：W4 第一轮剥离 `:created-at`，第二轮剥离
   `:model-entry-id` 后，`app-check` 在 B 恢复目录上退出 0（store 打开、
   sessions=2、gateway 计数正常）。
3. **触发点**：启动 → `effective-config` / `merge-persisted-config` → `model-configuration` → `clean-model-section` → 模型适配器 `normalize-connection` / `normalize-model` 的封闭字段校验。持久化连接/模型携带应用元数据，不在 `model-adapter.config/connection-keys` / `model-keys` 中。
4. **阻塞级别：修复前 P0（发布阻断）**。E2E 22 是迁移/恢复路径；W4 两轮
   投影修复后 B 启动已健康，但完整 E2E 22 复跑完成前仍不能把 22 写成 PASS。
5. **原始 ClassCast 的完整堆栈未被保留**：`start-system!` 的 catch 只保存 `:message`/`:class`，不打印 stack；`app.attempt*.log` 只有摘要。下面给出修复后当前树的完整堆栈与原始触发链的代码级定位，不伪造原始行号。
6. **W5 已修另一半启动阻断**：`system.clj` 的 `ReentrantLock` import 从
   `java.util.concurrent` 改为 `java.util.concurrent.locks`；B 原 data-dir 的
   W5 repro 实例已停止、锁已释放。模型投影的 `:created-at` /
   `:model-entry-id` 已由 W4 两轮修复；缺口记录见 `DEBT.md` D-05。
7. **W4 第二轮修复**：`model-app-keys` 增加 `:model-entry-id`；B 的
   `app-check` 已 exit 0。证据 `e2e22-b-rerun-check3.log`。
8. **E2E 22 当前为 PARTIAL**：B UI `historyReadable=true`，但
   `deletedButton=0`、`deletedVisible=false`；CLI 备份排除了已删除会话。
   完整脚本后续遇到 `STORAGE_LOCKED` 与 no-sync 连接拒绝（harness 锁/顺序），
   include-deleted/no-sync 未完成。不能写 PASS。

## 2. 当前树完整堆栈（W4 修复后）

命令：

```bash
cd /home/kunweiz/Desktop/vibe/Herdchestra
/tmp/pixi run clojure -M:server -i /tmp/hc-w7-reg/diag_model_config.clj
```

输出（节选，完整见 `e2e22-diag-model-config.log`）：

```text
:CLASS clojure.lang.ExceptionInfo
:MESSAGE configuration contains a field the adapter does not understand
:DATA {:error/kind :invalid-model-adapter-config,
       :reason :unknown-field,
       :path [],
       :unknown [:created-at]}
clojure.lang.ExceptionInfo: configuration contains a field the adapter does not understand
  at hc.server.model_adapter.config$invalid_BANG_.invokeStatic(config.clj:44)
  at hc.server.model_adapter.config$invalid_BANG_.invoke(config.clj:42)
  at hc.server.model_adapter.config$check_keys_BANG_.invokeStatic(config.clj:54)
  at hc.server.model_adapter.config$check_keys_BANG_.invoke(config.clj:49)
  at hc.server.model_adapter.config$normalize_connection.invokeStatic(config.clj:308)
  at hc.server.model_adapter.config$normalize_connection.invoke(config.clj:306)
  at clojure.core$mapv$fn__8565.invoke(core.clj:7063)
  at clojure.lang.PersistentVector.reduce(PersistentVector.java:418)
  at clojure.core$reduce.invokeStatic(core.clj:6968)
  at clojure.core$mapv.invokeStatic(core.clj:7054)
  at clojure.core$mapv.invoke(core.clj:7054)
  at hc.server.model_adapter.config$normalize_connections.invokeStatic(config.clj:429)
  at hc.server.model_adapter.config$normalize_connections.invoke(config.clj:412)
  at hc.server.model_adapter.config$normalize_config.invokeStatic(config.clj:575)
  at hc.server.model_adapter.config$normalize_config.invoke(config.clj:568)
  at hc.server.model_adapter$create_model_port.invokeStatic(model_adapter.clj:84)
  at hc.server.model_adapter$create_model_port.invoke(model_adapter.clj:70)
```

输入模型段（`diag_model_config.clj`）：

```clojure
{:connections
 [{:updated-at 2000
   :name "Local Ollama (E2E)"
   :base-url "http://127.0.0.1:11434/v1"
   :enabled? true
   :models {"qwen3.6:27b" {...}}
   :id "local-ollama"
   :kind :openai-compatible
   :connection-id "local-ollama"
   :created-at 1000
   :model-order ["qwen3.6:27b"]}]}
```

`model-adapter.config/connection-keys` 未包含 `:created-at`，所以 `check-keys!` 拒绝。

### 2.1 W4 第一次修复后的剩余字段 `:model-entry-id`

W4 剥离 `:created-at` 后，用 `/tmp/hc-w7-reg/data-e2e22-b-rerun` 复跑仍
`MODEL_PORT_FAILED`。完整堆栈（`e2e22-diag-model-config2.log`）：

```text
:DATA {:error/kind :invalid-model-adapter-config,
       :reason :unknown-field,
       :path [:models 0],
       :unknown [:model-entry-id]}
  at hc.server.model_adapter.config$check_keys_BANG_.invokeStatic(config.clj:54)
  at hc.server.model_adapter.config$normalize_model.invokeStatic(config.clj:198)
  at hc.server.model_adapter.config$normalize_model.invoke(config.clj:170)
  at hc.server.model_adapter.config$normalize_connection$fn__100353.invoke(config.clj:325)
  at hc.server.model_adapter.config$normalize_connection.invokeStatic(config.clj:327)
  at hc.server.model_adapter.config$normalize_connections.invokeStatic(config.clj:429)
  at hc.server.model_adapter.config$normalize_config.invokeStatic(config.clj:575)
  at hc.server.model_adapter$create_model_port.invokeStatic(model_adapter.clj:84)
```

模型 map 仍带 `:model-entry-id`；`model-adapter.config/model-keys` 未包含该键。
W4 已在第二轮把它从投影剥离（保留 `:id`）；B 的 `app-check` 随后 exit 0。
证据：`plans/evidence/e2e-regression-01-02-22/e2e22-diag-model-config2.log`、
`e2e22-b-rerun-check3.log`。

## 3. 原始 ClassCast 触发链（W4 修复前）

代码路径：

```text
system/start-internal!
  → effective-config
  → merge-persisted-config
  → model-configuration
  → clean-model-section
  → without-nil-values
  → clojure.core/into {}
  → ClassCastException: Character cannot be cast to Map$Entry
```

W4 的定位：持久化 `:models` 是 map 时，旧 `clean-model-section` 用 `mapv without-nil-values` 展开 MapEntry；`without-nil-values` 对字符串又按字符执行 `into {}`，触发 `Character → Map$Entry`。修复后 `:models` 为 map 时按 `[id model]` 归一化，非 map 原样返回。

证据：
- 原始失败摘要：`e2e22-start-b.log`、`e2e22-b-check-current.log`
- W4 回归测试：`test/hc/server/app/system_test.clj:508` `persisted-model-map-is-normalized-without-character-map-entries`
- 修复代码：`src/hc/server/app/system.clj` `clean-model-section` / `without-nil-values`

## 4. 最小复现

### 4.1 从备份恢复到全新 B 目录

```bash
ROOT=/tmp/hc-w7-reg/data-e2e22-b-rerun
rm -rf "$ROOT" && mkdir -p "$ROOT"
HC_BACKUP_PASSWORD='E2E22-correct-horse' \
  /tmp/pixi run clojure -M:server -m hc.server.backup.cli restore \
  --db-path "$ROOT/db" --data-dir "$ROOT" \
  --target-root /tmp/hc-w7-reg/e2e22/backups \
  --backup-id 401f2ece-d2da-4adb-95c7-7d961d28a150 --mode replace \
  --password-file /tmp/hc-w7-reg/e2e22/pw/password.txt
```

结果：`:ok? true`、`:status :complete`、`:store-observed? true`。

### 4.2 启动自检

```bash
cd /home/kunweiz/Desktop/vibe/Herdchestra
/tmp/pixi run clojure -M:server -m hc.server.app --check --port 0 \
  --data-dir /tmp/hc-w7-reg/data-e2e22-b-rerun \
  --workspace-root /tmp/hc-w7-reg/work-e2e22-b-rerun \
  --static-root resources/public
```

结果：exit 1；health `:models {:configured? false}`，`:component-errors {:model-error {:code :MODEL_PORT_FAILED ...}}`。store 打开、`sessions {:count 2}`。

### 4.3 只读复现模型段

```bash
/tmp/pixi run clojure -M:server -i /tmp/hc-w7-reg/diag_model_config.clj
```

结果：`{:unknown [:created-at]}`；完整堆栈见 `e2e22-diag-model-config.log`。

## 5. 可疑文件/行

| 文件 | 位置 | 作用 |
|---|---|---|
| `src/hc/server/app/system.clj` | `without-nil-values`（约 131）、`clean-model-section`（约 160）、`model-configuration`（约 195）、`merge-persisted-config`（约 210） | 持久化模型段投影；W4 修复了 `:models` map，但仍把连接元数据原样送入适配器 |
| `src/hc/server/model_adapter/config.clj` | `connection-keys`（约 13）、`check-keys!`（约 49）、`normalize-connection`（约 306）、`normalize-connections`（约 412） | 封闭字段校验；`:created-at` 不在白名单 |
| `src/hc/server/app/settings.clj` | `connection-list`（约 37）、`merge-model-config`（约 98） | 持久化连接归一化；需确认 `:created-at`/`:updated-at`/`:model-order` 是否应在投影层剥离 |
| `src/hc/server/app/session_persistence.clj` | `read-config` 路径 | 恢复后的 config 文档携带连接元数据 |
| `src/hc/server/app/snapshot.clj` | `settings-view`/`settings-models-view` | 目前不是本次异常的栈顶；W5 正在改，需回归时一起看 |

## 6. 建议（不代改源码）

- 优先在 `model-configuration` / `clean-model-section` 投影层剥离应用元数据（如 `:created-at`、`:updated-at`、`:model-order` 等），只把适配器 `connection-keys`/`model-keys` 认可的字段送入 `create-model-port`；不要简单把 `:created-at` 加进适配器白名单，否则会把应用元数据泄漏给适配器。
- 修复后重跑 4.2 的 `app-check`，再跑完整 E2E 22（B UI、`verify_store.clj`、no-sync、include-deleted）。
- 当前 `/tmp/hc-w7-reg/data-e2e22-b/db` 有 W5 进程持锁；本诊断使用独立恢复目录 `data-e2e22-b-rerun`，未杀进程。
- 原始 ClassCast 的完整 stack 需要 W4 在修复前保留的日志；当前 app 的 `start-system!` 只返回 message/class，后续应避免只保存摘要而丢弃 stack（属于诊断可观测性建议，不在本轮改源码）。
