# Store recovery 手工验证（干净 data-dir 的负向路径）

- 日期：2026-11-09
- 工作树：HEAD `1623d4e`（tag `v0.0.2`）+ 未提交改动
- 基础命令：`timeout -k 10 120 clojure -M:server -m hc.server.app --check --data-dir <DATA>`
- 隔离方式：每个场景用独立 `mktemp -d` 根目录；进程结束后在 `finally` 中删除；未启动 Sente/HTTP。
- 说明：首次尝试时 `src/hc/server/app/system.clj` 正被其它 worker 修改，出现 `Syntax error ... Unmatched delimiter`，那几轮不作为证据。下表全部来自该文件恢复可加载（`(require 'hc.server.app.system)` → `:load-ok`）后的重跑。

## 1. 前置：新建 data-dir 的实际产物

命令（`<DATA>` 为全新临时路径）：

```text
timeout -k 10 120 clojure -M:server -m hc.server.app --check --data-dir <DATA>
```

期望：`initialized.edn` 与 store 被创建；退出码允许非零（当时已知 `STOP_NOT_CONFIRMED` 缺陷）。

实际：`exit 0`。创建：

```text
initialized.edn (size=115, mode=0600)
db/VERSION (6), db/data.mdb (122880), db/lock.mdb (16512),
db/store.edn (195), db/store.lock (0), db/write-protocol.lock (0)
blobs/            (空)
worktrees/        (空)
fleet/state.edn (431)
artifacts/        (空)
extensions/catalog.edn (131)
maintenance/state.lock (0)
model-auth/key.bin (32)
secrets/master.key (44)
speech/audio/ speech/recordings/ (空)
```

PASS（产物创建）。本次 `--check` 正向路径 `exit 0`，未复现 `STOP_NOT_CONFIRMED`。

## 2. 负向 1（核心）：初始化后 store 目录缺失

命令：

```text
mv <DATA>/db <ROOT>/db.saved
timeout -k 10 120 clojure -M:server -m hc.server.app --check --data-dir <DATA>
```

期望：`exit 1`；输出含 `:STORE_MISSING_AFTER_INIT`；不得重建 `db/`；给出可操作恢复提示。

实际：`exit 1`。stderr（单行 EDN，摘录）：

```clojure
{:error {:code :STORE_MISSING_AFTER_INIT,
         :error :STORE_MISSING_AFTER_INIT,
         :message "the store directory is missing although this data directory contains prior state",
         :data {:db-path "<DATA>/db", :data-dir "<DATA>"}},
 :hint "Do not initialize a fresh store over a data directory that already holds sessions or backups. Restore the store or choose another data directory.",
 :recovery-entry {:action :operator,
                  :options [:inspect-directory :restore-from-backup :choose-another-path]},
 :path "<DATA>/db",
 :cleanup {:stopped? true, :drained? true, :failures [], :components {}, :store-closed? true}}
```

断言结果：

- `test -e <DATA>/db` → false（未被重新创建）
- `<DATA>/initialized.edn` 仍在
- 恢复提示同时给出 backup/restore 选项与“换一个 data-dir”

PASS。

## 3. 负向 2（损坏）：store 文件被写入垃圾

命令：把 `db/data.mdb` 前 4096 字节替换为随机字节（文件大小保持 122880），然后：

```text
timeout -k 10 120 clojure -M:server -m hc.server.app --check --data-dir <DATA>
```

期望：`exit 1`；不得静默新建空库；原文件不得被“新建库”替换（比较 size/mtime）。

实际：`exit 1`。stderr（摘录）：

```clojure
{:error {:error :STORAGE_OPEN_FAILED,
         :message "cannot open the Datalevin environment",
         :data {:path "<DATA>/db"},
         :cause "Fail to open database: #error {\n :cause \"MDB_INVALID: File is not an LMDB file\"\n ..."
         ...}}
```

断言结果：

- `data.mdb` 运行前（污染后）：size=122880, mtime=1794187543.627
- 运行后：size=122880, mtime=1794187543.627（未被运行改写或替换）
- 目录仍为 `VERSION data.mdb lock.mdb store.edn store.lock write-protocol.lock`，没有新建空库文件

PASS。

## 4. 负向 3（只读）：data 目录不可写

### 4a. 按任务描述只读 `<DATA>` 本身

命令：

```text
chmod -w <DATA>        # mode 0555
timeout -k 10 120 clojure -M:server -m hc.server.app --check --data-dir <DATA>
```

期望（任务描述）：`exit 1`，错误指向不可写。

实际：`exit 0`，`{:store {:open? true, :path "<DATA>/db"} ...}`。
原因：`<DATA>/db` 及其文件仍是 0755/0644，store 可以正常打开和写入；只有父目录 `data/` 只读。

**PASS (justified)**：`--check` 需要的路径是 store 环境与已经存在的子目录；`initialized.edn`、`fleet/state.edn`、`maintenance/state.lock`、`blobs/`、`worktrees/` 都已存在，各自可写，本轮不需要在只读的 `<DATA>` 下创建新条目。因此 `exit 0` 是正确可用状态，不是把旧库当新安装空库。真正不可写的 store 由 4b 覆盖。

### 4b. 补充：store 子目录和文件也不可写

命令：

```text
chmod 0555 <DATA>/db; chmod 0444 <DATA>/db/*
timeout -k 10 120 clojure -M:server -m hc.server.app --check --data-dir <DATA>
```

修复前实际：`exit 1`，stderr：

```clojure
{:error {:error :STORAGE_LOCKED,
         :message "cannot open the store lock file",
         :data {:path "<DATA>/db/store.lock"},
         :cause "<DATA>/db/store.lock",
         :code :STORAGE_LOCKED},
 :hint "Another process holds the store lock at <DATA>/db. Stop the other process; this process did not open the store.",
 :recovery-entry {:action :operator,
                  :options [:stop-other-process :retry :choose-another-path]},
 :path "<DATA>/db"}
```

**修复前 FAIL（诊断措辞）**：退出码正确且未静默新建，但把 EACCES 归因为“另一个进程持有锁”，没有指出目录/文件不可写。

修复后复验（同日，`system.clj` 可正常加载；命令同上）：

```clojure
{:error {:error :STORAGE_NOT_WRITABLE,
         :message "the store lock path is not writable (permissions or a read-only mount): <DATA>/db/store.lock",
         :data {:path "<DATA>/db/store.lock"},
         :cause "<DATA>/db/store.lock",
         :code :STORAGE_NOT_WRITABLE},
 :hint "The store path at <DATA>/db is not writable (permissions or a read-only mount). Change permissions or choose a writable directory; nothing was changed.",
 :recovery-entry {:action :operator,
                  :options [:inspect-directory :restore-from-backup :choose-another-path]},
 :path "<DATA>/db"}
```

实际：`exit 1`；`data.mdb` size 仍为 122880（未被替换）；message 指出具体路径；hint 不再出现 “another process”。

**PASS（修复后）**：`:STORAGE_NOT_WRITABLE` 与真实锁竞争 `:STORAGE_LOCKED` 分离；`storage/error.clj` 的 `unwritable?` 用 `AccessDeniedException`、`ReadOnlyFileSystemException`、`FileSystemException` 的 reason，以及原因链消息（`permission denied` / `operation not permitted` / `read-only file system`）判定权限失败；`lock.clj` 与 `datalevin.clj` 共用该判定，避免把 EACCES 当锁竞争。

补充复验（`db/` 可写、仅 `db/data.mdb` 0444）：修复前是 `:STORAGE_OPEN_FAILED`；修复后：

```clojure
{:error {:error :STORAGE_NOT_WRITABLE,
         :message "the store path is not writable (permissions or a read-only mount): <DATA>/db",
         :data {:path "<DATA>/db"},
         :cause "Fail to open database: #error {\n :cause \"Permission denied\" ..."},
 :hint "The store path at <DATA>/db is not writable (permissions or a read-only mount). Change permissions or choose a writable directory; nothing was changed.",
 :path "<DATA>/db"}
```

`exit 1`；`data.mdb` size 仍为 122880。引擎打开路径（`storage/engine/datalevin.clj`）也用同一判定，不会把权限失败归为 `:STORAGE_OPEN_FAILED` 或 `:STORAGE_CORRUPT`。

## 5. 对照：另一个全新 data-dir

命令：

```text
timeout -k 10 120 clojure -M:server -m hc.server.app --check --data-dir <DATA2>
```

期望：`initialized.edn` 被正确创建；若因已知 `STOP_NOT_CONFIRMED` 返回非零，不算本任务失败。

实际：`exit 0`；`initialized.edn` 存在；创建目录集合与第 1 条相同。已知 `STOP_NOT_CONFIRMED` 未复现。

PASS（初始化创建）。

## 结论

- “初始化后 store 缺失”：**PROVEN**。`exit 1`、`:STORE_MISSING_AFTER_INIT`、`db/` 未重建、给出 `:restore-from-backup` 等恢复选项。
- “store 损坏”：**PROVEN**。Datalevin `MDB_INVALID` 导致 `exit 1`；`data.mdb` 的 size/mtime 未被运行改写，没有静默覆盖为空库。
- “只读 data 目录”：4a **PASS (justified)**——父目录只读、store 与所需子目录可写时 `exit 0` 是正确的可用状态；4b **PASS（修复后）**——store 子目录/文件只读时 `exit 1` + `:STORAGE_NOT_WRITABLE`，message/hint 指向具体路径与权限/只读挂载，不再提“另一个进程”。`chmod` 场景没有把旧库当新安装空库处理。
- 关于 `STOP_NOT_CONFIRMED`：本次重跑未复现；正向 `--check` 与全新目录对照均 `exit 0`。因此无 `INCONCLUSIVE (blocked by STOP_NOT_CONFIRMED)` 项。
- 清理：所有临时根目录均已删除；本复验轮次的 `root_exists=false`。
- 本轮修复文件：`src/hc/server/storage/error.clj`（新增 code 与判定）、`src/hc/server/storage/lock.clj`（锁路径分类）、`src/hc/server/storage/layout.clj`（hint）、`src/hc/server/storage.clj`（建目录分类）、`src/hc/server/storage/engine/datalevin.clj`（引擎打开分类）；新增 `test/hc/server/storage/engine/datalevin_test.clj`。未修改 `src/hc/server/app/system.clj`。
