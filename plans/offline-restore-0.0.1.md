# 离线备份/恢复 CLI 实跑记录（0.0.1）

- 日期：2026-11-05
- 隔离根目录：`/tmp/hc-offline-vzwJBd3u`
- 未修改产品源码，未执行 git 操作。
- 入口：`pixi run backup` 等价于 `clojure -M:server -m hc.server.backup.cli`。

> 后续状态（2026-11-05 复检）：D1/D2/D3 的主要路径在当前树已修复并通过定向复检；
> D4（重复 replace）也已修复：同一备份同一类别第二次 replace 返回
> `:status :already-imported`，不新增恢复点。见 `HACK.md` §13.4 /
> §14.4 与 `plans/release-notes-0.0.1.md` §4.4。本文保留早期失败原文作为
> 证据；§3.11 与 D4 的“重新应用”描述是修复前历史，不再代表当前行为。

## 1. 隔离数据与种子

源数据目录：`/tmp/hc-offline-vzwJBd3u/source`
- store：`source/db`
- blobs：`source/blobs`
- extensions：`source/extensions`
- workspaces：`source/workspaces`
- 备份目标：`source/backups`（CLI 默认路径）、`source/backups-enc`（显式加密 workaround）

种子脚本：`/tmp/hc-offline-vzwJBd3u/seed.clj`，命令：

```bash
HC_OFFLINE_SOURCE=/tmp/hc-offline-vzwJBd3u/source \
  clojure -M:server -e '(load-file "/tmp/hc-offline-vzwJBd3u/seed.clj")'
```

退出码 0，输出：

```text
{:seeded true, :blob-id "sha256-f60e6a81f2dcb75f3d2174bf74517ca82667331c819b1de2a4ae689f74d48164", :session-id "s1", :memory-id "mem1", :worldbook-id "wb1", :schedule-id "sched1", :tool-id "mcp1"}
```

种子包含：
- 会话 `s1`（owner-state、消息 `m1`、版本 `m1-v1`、附件 `att1`，附件字节 `attachment evidence`）
- 记忆 `mem1`（`:status :active`）
- 世界书 `wb1` + 条目 `e1`
- 定时任务 `sched1`（`:state :enabled`，`next-run 9999999999999`）
- MCP 工具 `mcp1`（`:enabled? true`）
- 连接 `conn1` 含 `:api-key "super-secret"`、`:api_key "snake-secret"`、`:session-token "login-secret"`

## 2. 结果总表

| 步骤 | 结果 | 证据 |
|---|---|---|
| 1. 隔离系统与种子 | PASS | `evidence/seed.out`、`source/db` |
| 2a. CLI 默认加密备份 | **FAIL**：生成 `:encryption :none` | `evidence/backup.out` |
| 2b. CLI `--encrypt true` | **FAIL**：`expected true or false {:value nil}` | `evidence/backup-encrypted.out` |
| 2c. 库路径显式加密备份（workaround） | PASS：`:encryption :aes-gcm` | `evidence/backup-encrypted-library.out` |
| 2d. 密码 >=12 | PASS（库路径；11 位拒绝） | `evidence/password-short.out` |
| 2e. 秘密默认排除 | PASS：移除 2 个 secret + 1 个 non-exportable，payload 无密钥 | `evidence/verify-secrets.out` |
| 3a. CLI 恢复到全新目录 | **FAIL**：`BACKUP_COMMAND_EPOCH_WINDOW_MISSING` | `evidence/restore.out` |
| 3b. 预置 app gate 后 CLI 恢复（workaround） | PASS | `evidence/restore-gate.out` |
| 3c. 恢复内容核验 | PASS | `evidence/verify-target.out` |
| 4a. 错误密码不改资料 | PASS：exit 1，`:BACKUP_WRONG_PASSWORD`，sentinel 仍在 | `evidence/restore-wrong-password.out` |
| 4b. 重复 merge 导入幂等 | PASS：第二次 `:already-imported`，恢复点 1 | `evidence/restore-merge-1.out`、`restore-merge-2.out` |
| 4c. 重复 replace 导入 | 修复前历史：内容仍各 1 份，但重新应用并新增恢复点；当前已改为 `:already-imported` | `evidence/restore-repeat.out`、`/tmp/hc-docs-final-restore-repeat.log` |
| 4d. 恢复后计划不自动运行 | PASS：`:state :paused`、`:next-run nil`、0 个 run | `evidence/verify-target.out` |

## 3. 命令、退出码与关键输出

### 3.1 CLI 默认备份（FAIL：未加密）

```bash
HC_BACKUP_PASSWORD='offline-pass-123' \
clojure -M:server -m hc.server.backup.cli backup \
  --db-path /tmp/hc-offline-vzwJBd3u/source/db \
  --data-dir /tmp/hc-offline-vzwJBd3u/source \
  --target-root /tmp/hc-offline-vzwJBd3u/source/backups \
  --recovery-root /tmp/hc-offline-vzwJBd3u/source/recovery \
  --extensions-root /tmp/hc-offline-vzwJBd3u/source/extensions \
  --blob-root /tmp/hc-offline-vzwJBd3u/source/blobs \
  --workspace-store-root /tmp/hc-offline-vzwJBd3u/source/workspaces \
  --now 2026-11-05T00:00:00Z
```

退出码 0，但关键输出：

```text
:encrypted? false, :encryption :none, :id "05246aaf-1226-4e85-8b9c-bbae8b97cbeb"
:secrets {:included? false, :removed {:secret 2, :non-exportable 1}}
```

违反 §7.2.1“新建备份默认开启密码加密”。见缺陷 D1。

### 3.2 CLI `--encrypt true`（FAIL：参数解析）

```bash
... backup ... --encrypt true
```

退出码 1：

```text
{:ok? false, :error {:code :BACKUP_FAILED, :message "expected true or false", :details {:value nil}}}
```

`--encrypt=true` 同样失败。见缺陷 D2。`--include-secrets true` 同样失败（`evidence/backup-include-secrets.out`）。

### 3.3 库路径显式加密备份（workaround，PASS）

```bash
clojure -M:server -e '(require (quote [hc.server.backup.offline :as offline])) (println (pr-str (offline/backup! {... :password "offline-pass-123" :encrypt? true :now "2026-11-05T00:00:00Z"})))'
```

退出码 0，备份 ID `13e03265-a815-482f-90f0-ca8ce11c985f`：

```text
:encrypted? true, :encryption :aes-gcm, :crypto-version 1
:secrets {:included? false, :removed {:secret 2, :non-exportable 1}}
:attachments {:count 1, :bytes 19}, :incomplete-attachments []
```

### 3.4 密码 >=12（PASS，库路径）

11 位密码 `short-pass1` 调用 `offline/backup!`：

```text
exit 1
{:error :BACKUP_PASSWORD_TOO_SHORT, :min-length 12, :length 11}
```

目标目录 `source/backups-short` 中 `.hcb` 对象数 0。CLI 路径因 D1/D2 无法进入加密校验，不能作为密码策略证据。

### 3.5 秘密默认排除（PASS）

用密码打开备份并检查 `:connections`：

```text
{:id "conn1", :name "Offline Provider",
 :has-api-key false, :has-api_key false, :has-session-token false,
 :secrets {:included? false, :removed {:non-exportable 1, :secret 2}}}
```

### 3.6 CLI 恢复到全新目录（FAIL：命令窗口缺失）

```bash
HC_BACKUP_PASSWORD='offline-pass-123' \
clojure -M:server -m hc.server.backup.cli restore \
  --db-path /tmp/hc-offline-vzwJBd3u/target/db \
  --data-dir /tmp/hc-offline-vzwJBd3u/target \
  --target-root /tmp/hc-offline-vzwJBd3u/source/backups-enc \
  --recovery-root /tmp/hc-offline-vzwJBd3u/target/recovery \
  --extensions-root /tmp/hc-offline-vzwJBd3u/target/extensions \
  --blob-root /tmp/hc-offline-vzwJBd3u/target/blobs \
  --workspace-store-root /tmp/hc-offline-vzwJBd3u/target/workspaces \
  --backup-id 13e03265-a815-482f-90f0-ca8ce11c985f --mode replace \
  --no-prior-app-commands true
```

退出码 1：

```text
:status :failed, :error {:code :BACKUP_COMMAND_EPOCH_WINDOW_MISSING ...},
:rolled-back? true, :store-unchanged? true, :needs-recovery? false
```

`--no-prior-app-commands` 被 CLI 映射为 `:no-prior-app-commands`，而 `offline/restore!` 读取
`:no-prior-app-commands?`，所以标志没有生效。见缺陷 D3。

### 3.7 预置 app gate 后 CLI 恢复（workaround，PASS）

在 target store 写入一个合法 app gate：

```clojure
{:command-epoch 1 :retired-through 0 :receipts {} :receipt-index {}}
```

再执行同一 restore（去掉无效标志），退出码 0：

```text
:status :complete
:permissions {:schedules :paused, :auto-backup :disabled,
              :device-control :disabled, :full-access :disabled,
              :external-write :disabled, :implicit-authority? false ...}
:recovery-point {:id "rp-03c9b502-...", :bytes ..., :sha256 ...}
```

### 3.8 恢复内容核验（PASS）

`evidence/verify-target.out`：

```text
{:session-title "Offline session",
 :message-version-content "hello offline",
 :attachment-ref-attachment-id "att1",
 :attachment-ref-blob-id "sha256-f60e6a81...",
 :attachment-bytes "attachment evidence",
 :attachment-present? true,
 :memory-body "offline memory", :memory-status :candidate,
 :worldbook-book? true, :worldbook-entry? true,
 :worldbook-entry-body "lore body",
 :schedule-state :paused, :schedule-next-run nil,
 :scheduler-run-count 0,
 :tool-enabled? false,
 :tool-connection {:status :unconnected ...},
 :tool-auth {:state :none},
 :safety-schedules :paused,
 :safety-memories :pending-review,
 :safety-implicit-authority false,
 :app-gate-epoch 2, :app-gate-retired 1,
 :recovery-point-count 2,
 :sessions-blob-ref-count 1}
```

说明：
- 记忆为 `:candidate` 是 §7.2.12 的预期：replace 未显式确认，不复活为 active。
- 定时任务 `:state :paused`、`:next-run nil`，且没有任何 scheduler run。
- 工具 `:enabled? false`、连接未连接、auth 清空；恢复安全文档记录 schedules/memories 的暂停。
- app gate 从 1 提升到 2，旧 epoch 被 fence。

### 3.9 错误密码（PASS）

```bash
HC_BACKUP_PASSWORD='wrong-pass-123' ... restore --db-path /tmp/hc-offline-vzwJBd3u/wrong/db ...
```

退出码 1：

```text
{:ok? false, :error {:code :BACKUP_WRONG_PASSWORD,
                     :details {:reason :key-check,
                               :store-observed? true,
                               :store-unchanged? true}}}
```

wrong 目录的 sentinel `p/sentinel` 仍在，`:session-record` 为 nil；维护日志为
`:failed-before-commit`、`:needs-recovery? false`。

### 3.10 重复 merge 导入（PASS）

`target-merge` 第一次 merge：`:status :complete`，恢复点 1。
第二次 merge：`:status :already-imported`，恢复点仍为 1；session/memory/schedule 计数均为 1。

### 3.11 重复 replace 导入（修复前历史：PARTIAL）

> 修复前记录：以下“重新应用”行为已被 server-audit 修复；当前同一备份同一
> 类别第二次 replace 返回 `:status :already-imported`，不新增恢复点。
> 证据：`/tmp/hc-docs-final-restore-repeat.log`。

同一加密备份对 `target` 再执行一次 `--mode replace`：退出码 0，`:status :complete`，
session/memory/schedule 计数仍为 1（没有增殖），但恢复点从 2 增至 3，ledger 被重写。
`merge/build-plan` 的 `digest-match?` 只在 `:mode :merge` 时成立；replace 不短路。
§7.2.13 的幂等承诺明确写的是“第二次合并”，因此这是已知行为边界，不是数据损坏；
若发布要求 replace 也完全 no-op，需要另立条目。

## 4. 缺陷清单

| 编号 | 文件:行 | 现状 | 影响 | 建议 |
|---|---|---|---|---|
| D1 | `src/hc/server/backup/offline.clj:275`；`src/hc/server/backup/core.clj:85` | `offline/backup!` 无条件传 `:encrypt? encrypt?`；`encrypt?` 为 nil 时 `normalize-create-options` 看到 key 存在，按显式 false 处理 | CLI/库调用不传 `:encrypt?` 时默认**不加密**，违反 §7.2.1；密码策略也不执行 | 只在 `encrypt?` 非 nil 时 assoc `:encrypt?`；或 `core` 用 `(boolean (:encrypt? opts))` 但区分“未提供”与“显式 false” |
| D2 | `src/hc/server/backup/cli.clj:121-126,137-138` | `options` 的 `select-keys` 不含 `:encrypt`/`:include-secrets`，但 cond-> 对它们调用 `parse-bool` | `--encrypt true` 和 `--include-secrets true` 一律 exit 1：`expected true or false {:value nil}` | 把 `:encrypt :include-secrets` 加入 `select-keys`；或仅在 raw 含该键且非 nil 时 update |
| D3 | `src/hc/server/backup/cli.clj:124,134-135`；`src/hc/server/backup/offline.clj:337` | CLI 产出 `:no-prior-app-commands`，offline 读取 `:no-prior-app-commands?` | 全新目标没有 app gate 时，CLI 无法恢复，报 `BACKUP_COMMAND_EPOCH_WINDOW_MISSING`；`--no-prior-app-commands true` 无效 | 统一键名（建议 CLI 产出带 `?` 的键，或 offline 读取两者） |
| D4 | `src/hc/server/backup/merge.clj:592` | `digest-match?` 要求 `:mode :merge` | 修复前：重复 replace 会重新应用并新建恢复点。修复后：同一备份同一类别第二次 replace 返回 `:status :already-imported`，不新增恢复点 | 已修；`/tmp/hc-docs-final-restore-repeat.log`；§3.11 保留修复前历史 |

## 5. 证据路径

根：`/tmp/hc-offline-vzwJBd3u`

- `evidence/backup.out`、`backup.err`：CLI 默认备份（未加密）
- `evidence/backup-encrypted.out`、`backup-encrypted.err`：CLI `--encrypt true` 失败
- `evidence/backup-encrypted-library.out`、`.err`：库路径加密备份
- `evidence/password-short.out`：11 位密码拒绝
- `evidence/verify-secrets.out`：秘密排除核验
- `evidence/restore.out`：CLI 全新目录恢复失败
- `evidence/restore-gate.out`：预置 gate 后恢复成功
- `evidence/verify-target.out`：恢复内容核验
- `evidence/restore-wrong-password.out`：错误密码
- `evidence/restore-merge-1.out`、`restore-merge-2.out`：merge 幂等
- `evidence/restore-repeat.out`：replace 重复
- `source/backups`、`source/backups-enc`、`target/`、`target-merge/`、`wrong/`：数据现场
- `source/db`、`target/db`、`target-merge/db`、`wrong/db`：可重新打开核验

## 6. 结论

- 加密备份的核心（AES-GCM、PBKDF2、秘密默认排除、错误密码不改资料）通过。
- 恢复内容（会话、记忆候选、世界书、附件字节、定时任务暂停、工具禁用、恢复安全文档、命令 epoch fence）通过。
- 发布阻断：D1 默认不加密、D2 CLI 无法开启加密/包含秘密、D3 全新目标无法用 CLI 恢复。
  这三项都在 `src/hc/server/backup/offline.clj` 与 `src/hc/server/backup/cli.clj`，
  属于本次“不改产品源码”的范围外修复；本记录只报告，不修改。
