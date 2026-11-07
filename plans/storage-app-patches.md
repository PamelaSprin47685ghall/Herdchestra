# Storage 审查遗留的 app 侧补丁

本文只记录 `src/hc/server/app/**` 与 `src/hc/server/sessions/registry.clj` 的未应用接线。
`src/hc/server/app/system.clj` 正由 server-app 修改，本文不直接改源码；行号以本快照为准，
合并时以函数/上下文定位，不要按行号硬贴。

范围外但相关的存储侧改动已经落地并通过测试：
`storage/open-close-store!` 的 `:STORAGE_DRAIN_INCOMPLETE`、
`storage/erase-session-derived!`、`backup.storage-adapter/restore-safety`、
恢复点只返回元数据、ledger 的 `:external-applied?` 与 `:retry-external?`。

---

## A. #15 启动时的 store presence 保护

**文件**：`src/hc/server/app/system.clj`
**现状（本快照）**：已经存在，不需要重复添加。

- `initialized-marker-path` / `marker-present?` / `prior-state-evidence?`：2413–2434
- `check-store-presence!`：2436–2457
- `write-initialized-marker!`：2459–2477
- 启动处 guard：2574–2576

```clojure
(let [_ (check-store-presence! config)
      entry (recovery/recovery-entry {:path (:db-path config)
                                      :create? true})]
  ...)
```

**语义**：数据目录已有 `initialized.edn`、维护日志、fleet 状态、非空 blob 或非空 worktree，
但 `:db-path` 不存在时，启动返回 `:STORE_MISSING_AFTER_INIT` blocked entry，不创建新空库。
首次运行的空数据目录仍允许 `:create? true`，成功后写 `initialized.edn`。

**若目标分支缺失，精确补丁**：

```diff
+(defn- initialized-marker-path [config]
+  (str (:data-dir config) "/initialized.edn"))
+
+(defn- marker-present? [config]
+  (.exists (io/file (initialized-marker-path config))))
+
+(defn- prior-state-evidence? [config]
+  (or (marker-present? config)
+      (.exists (io/file (:data-dir config) "maintenance" "state.edn"))
+      (.exists (io/file (:data-dir config) "fleet" "state.edn"))
+      (let [blobs (io/file (:data-dir config) "blobs")]
+        (and (.isDirectory blobs) (pos? (count (.listFiles blobs)))))
+      (let [worktrees (io/file (:worktree-root config))]
+        (and (.isDirectory worktrees) (pos? (count (.listFiles worktrees)))))))
+
+(defn- check-store-presence! [config]
+  (when (and (not (.exists (io/file (:db-path config))))
+             (prior-state-evidence? config))
+    (throw (ex-info "the current-state store is missing but this data directory was initialized"
+                    {:recovery-blocked
+                     {:status :blocked
+                      :error {:code :STORE_MISSING_AFTER_INIT
+                              :error :STORE_MISSING_AFTER_INIT
+                              :message "the store directory is missing although this data directory contains prior state"
+                              :data {:db-path (:db-path config)
+                                     :data-dir (:data-dir config)}}
+                      :hint "Do not initialize a fresh store over a data directory that already holds sessions or backups. Restore the store or choose another data directory."
+                      :path (:db-path config)
+                      :recovery-entry
+                      {:action :operator
+                       :options [:inspect-directory
+                                 :restore-from-backup
+                                 :choose-another-path]}}}))))
+
+(defn- write-initialized-marker! [config store]
+  (let [path (initialized-marker-path config)
+        file (io/file path)
+        value {:format-version 1
+               :store-id (get-in store [:opts :store-id])
+               :created-at (str (java.time.Instant/now))}]
+    (when-not (.exists file)
+      (io/make-parents file)
+      (spit file (pr-str value))
+      (try
+        (java.nio.file.Files/setPosixFilePermissions
+         (.toPath file)
+         (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
+        (catch Exception _ nil)))
+    value))
@@
-      (let [entry (recovery/recovery-entry {:path (:db-path config)
-                                            :create? true})]
+      (let [_ (check-store-presence! config)
+            entry (recovery/recovery-entry {:path (:db-path config)
+                                            :create? true})]
@@
           (let [store (:store entry)
+                _ (write-initialized-marker! config store)
                 _ (swap! partial-system assoc :store store)
```

**测试**（加到 `test/hc/server/app/system_test.clj`）：
1. 空数据目录首次启动：创建 store，`initialized.edn` 存在且 `:store-id` 与 `store-info` 一致。
2. 预置 `maintenance/state.edn` 或 `initialized.edn`、删除 `:db-path`：`start!` 返回 blocked，
   `:error :STORE_MISSING_AFTER_INIT`，磁盘上没有新 store 目录。
3. 预置非空 `blobs/`、删除 `:db-path`：同上。

---

## B. #22 进程 generation 共享

**文件**：`src/hc/server/app/system.clj`
**现状**：`recover-all!` 在 1552–1556 内联生成 generation 后丢弃；scheduler 启动（3159）
没有传 `:generation`，因此 scheduler 的 `:executor-generation` 与恢复扫描的
`:current-generation` 永远不同，`:live` 分支在生产不可达。

**精确修改**：

```diff
-(defn- recover-all!
-  [store]
+(defn- recover-all!
+  [store generation]
   (let [result (recovery/recover-all!
                 store
-                {:current-generation (recovery/new-generation)
+                {:current-generation generation
                  :now-ms (System/currentTimeMillis)})]
@@
-      (let [_ (check-store-presence! config)
+      (let [process-generation (recovery/new-generation)
+            _ (check-store-presence! config)
             entry (recovery/recovery-entry {:path (:db-path config)
                                             :create? true})]
@@
-                session-recovery (recover-all! store)
+                session-recovery (recover-all! store process-generation)
@@
-              (scheduler/start! {:storage store
+              (scheduler/start! {:storage store
                                  :registry session-registry
                                  :config live-scheduler-config
                                  :gateway gateway-handle
+                                 :generation process-generation
                                  :tick-ms (get-in config [:scheduler :tick-ms] 1000)})
@@
-                              :session-recovery session-recovery
+                              :session-recovery session-recovery
+                              :process-generation process-generation
```

**边界**：
- `scheduler/start!` 已接受 `:generation`；`durable-store` 用它写 run 义务的
  `:executor-generation`。
- Owner 的 report 义务保持 `:executor-generation 0`，符合 recovery 契约。
- 其他动作义务生产者（若以后新增）必须从同一个 `process-generation` 取值；
  不要各自调用 `recovery/new-generation`。

**测试**（加到 `test/hc/server/app/system_test.clj`）：
1. 启动后 `(get-in system [:process-generation])` 是非空字符串。
2. `(get-in system [:scheduler :generation])` 与 `:process-generation` 相等。
3. 启动恢复后新建的 scheduler run 义务，其 `:executor-generation` 等于
   `:process-generation`；`recover-all!` 的 `:current-generation` 用同一值
   （可用 `with-redefs` 捕获 `recovery/recover-all!` 的 opts）。

---

## C. 退出路径接入有序关闭与 drain

**文件**：`src/hc/server/app/system.clj`
**现状**：`stop!`（3492）先 `drain-commands!`（3466）停止命令入口，最后在 3568 直接
`storage/close-store!`。存储层的 `:accepting?` 屏障、drain 超时与 handoff 没有走
`open-close-store!`；关闭与在途提交之间只靠组件顺序。

**精确修改**：

```diff
         (do
           (when-let [store (:store system)]
-            (stop-one! components failures :store #(storage/close-store! store)))
+            ;; drain-commands! already stopped new work.  The store close now
+            ;; uses the ordered barrier too, so a late commit sees
+            ;; :STORAGE_CLOSING instead of a closed engine.
+            (stop-one! components failures :store
+                       #(if (storage/store-open? store)
+                          (storage/open-close-store!
+                           store
+                           {:drain-fn (fn [] true)
+                            :timeout-ms (long
+                                         (or (get-in system
+                                                     [:maintenance
+                                                      :drain-timeout-ms])
+                                             5000))})
+                          {:closed? true})))
           (if (or (seq @failures) (not (store-closed? system)))
```

**说明**：
- `:drain-fn (fn [] true)` 不是新的事实来源：真正的命令 drain 已由 `drain-commands!`
  完成；这里使用的是 `open-close-store!` 的 intake 屏障、关闭顺序和 closed 校验。
- 若 server-app 希望把 store drain 与命令 drain 合并，可把 `:drain-fn` 改为
  `(fn [] (empty? @(:owners system)))` 之类的真实检查，但必须保持幂等。
- `open-close-store!` 在 store 已关闭时会抛 `:STORAGE_CLOSED`，所以保留
  `store-open?` 分支，保证 `stop!` 幂等。

**测试**（加到 `test/hc/server/app/system_test.clj`）：
1. 正常启动/停止：`:stopped? true`、`:store-closed? true`；第二次 `stop!` 仍成功。
2. 在 `stop!` 的 store 关闭前尝试 `storage/commit-migration!`：得到
   `:STORAGE_CLOSING`，不是 native closed engine 错误。
3. `open-close-store!` 的 drain 返回 false 时（可用 `with-redefs`）：
   `:stopped? false`，store 保持 open，`components :store` 记录
   `:STORAGE_DRAIN_INCOMPLETE`。

---

## D. E2E 20：discard-session! 清理派生分区

**文件 1**：`src/hc/server/app/session_persistence.clj`，`delete-session!` 175–215
**现状**：只提交 `sessions` 分区的 session record、command 记录、index 与 blob refs；
`session/<id>`、diagnostics、materials、search 分区不清理。

**精确修改**：

```diff
                 (nil? stored)
-                {:status :missing}
+                (let [erased (storage/erase-session-derived! storage session-id)]
+                  {:status :missing
+                   :external-effects (:external-effects erased)})
@@
                 :else
-                (let [ids (remove #{session-id} (index-ids view))
+                (let [erased (storage/erase-session-derived! storage session-id)
+                      ;; The erasure commits the session-index update; the
+                      ;; view read before it is stale.
+                      view (read-view storage sessions-partition session-id)
+                      ids (remove #{session-id} (index-ids view))
                       sessions (records-of view session-kind)
@@
-                  {:status :deleted})))
+                  {:status :deleted
+                   :external-effects (:external-effects erased)})))
```

**顺序**：先 erasure，再读新 view，再提交 record/command/index/ref 删除。
erasure 失败时 session record 仍在，调用方可重试；不会出现“记录已删、派生数据还在”。
`:missing` 分支也执行 erasure，用于清理上次部分失败留下的派生数据。

**文件 2**：`src/hc/server/sessions/registry.clj`，`discard-session!` 780
**现状**：`:deleted` 只返回 `:status :discarded` 与 `:session-id`，丢失外部影响披露。

```diff
-                      :deleted {:status :discarded
-                                :session-id session-id}
+                      :deleted {:status :discarded
+                                :session-id session-id
+                                :external-effects (:external-effects result)}
```

**文件 3**：`src/hc/server/app/commands.clj`，`discard-temporary-session!` 870–873
**现状**：回执只有 `:discarded? true`，没有“外部服务内容不可撤销”的说明。

```diff
-                (reply reply-fn {:ok? true
-                                 :receipt {:status :accepted
-                                           :session-id session-id
-                                           :discarded? true}}))
+                (reply reply-fn {:ok? true
+                                 :receipt {:status :accepted
+                                           :session-id session-id
+                                           :discarded? true
+                                           :external-effects
+                                           (:external-effects result)}}))
```

**测试**：
- 已有 `test/hc/server/storage/session_erasure_test.clj` 覆盖真实 persistence delete +
  `erase-session-derived!`：owner partition、obligations、diagnostics、memory candidates、
  session index 全无残留，`:external-effects` 存在且 `:revocable? false`。
- 应用层新增 `test/hc/server/sessions/discard_storage_test.clj`：
  1. 用 `session-persistence/persistence` + `registry/registry` 提交一个 `:temporary? true`
     的 session，seed `session/<id>` owner-state、diagnostics request、materials memory
     candidate、instruction-session。
  2. 调 `(registry/discard-session! reg id)`。
  3. 断言 `:discarded`、`ports/read-session` 为 `:missing`、`session/<id>` 为空、
     diagnostics/materials 只剩其他 session、session index 不含 id、没有 `:deleted` 记录。
  4. 断言回执 `:external-effects` 含 `{:scope :external-services :revocable? false ...}`。
- 注意：模型/搜索/语音已经收到的内容不受本地舍弃撤销；测试只断言披露数据，不伪造撤销。

---

## E. 恢复后安全默认的启动读取（H9 app 侧）

**文件**：`src/hc/server/app/system.clj`，store 打开后（约 2577）
**现状**：`backup.storage-adapter` 已在同一 `restore-state!` 事务写 `config` 分区
`:restore-safety`；app 启动尚未读取。

**精确修改（最小）**：

```diff
           (let [store (:store entry)
+                restore-safety (backup-domain/restore-safety store)
                 _ (write-initialized-marker! config store)
@@
                               :session-recovery session-recovery
+                              :restore-safety restore-safety
                               :process-generation process-generation
```

**执行语义**（由设置/调度/工具/工作区各自消费）：
- `:permissions :schedules :paused`：导入的 schedule 已由存储写成 `:state :paused`；
  启动时不得自动 `resume`。
- `:permissions :auto-backup :disabled`：自动备份计划保持关闭，直到用户显式启用。
- `:permissions :tools/:skills`：工具/技能保持禁用待审；启动不自动测试连接。
- `:permissions :external-workspaces :relink-required`：外部工作区不自动授权。
- `:implicit-authority? false` 是硬断言；任何组件不得从恢复数据推导执行权限。

**测试**：
1. 恢复一个含 schedule 的备份；重启后 `system :restore-safety` 存在，
   `:permissions :schedules` 为 `:paused`，schedule 仍是 `:state :paused`。
2. 恢复后不产生自动模型/搜索请求；工具/技能 `:enabled? false`。
3. 用户显式启用后，`restore-safety` 记录保留（作为历史），但对应功能可按新设置运行。

---

## F. 合并顺序与冲突提示

1. 先合 A（若目标分支已有则跳过）与 B；两者只改 `start-internal!` 的 let 绑定和
   `recover-all!` 签名。
2. 再合 C；`stop!` 的 store 分支只改最后一行，冲突概率低。
3. 最后合 D；`delete-session!` 的 `:else` 分支要重新读 view，不要保留旧 `view` 的
   `:expected-version`。
4. E 依赖 D 之后的 `restore-safety` 读取；若 app 尚无权限消费方，先只暴露
   `:restore-safety`，不要伪造执行权限。
5. 所有行号以合并时为准；用函数名/上下文定位。

## G. 验证命令

```bash
clojure -M:test:server -n hc.server.backup.storage-adapter-regressions-test
clojure -M:test:server -n hc.server.backup.maintenance-test
clojure -M:test:server -n hc.server.backup.workspace-test
clojure -M:test:server -n hc.server.storage.session-erasure-test
```

应用 patch 后追加：

```bash
clojure -M:test:server -n hc.server.app.system-test
clojure -M:test:server -n hc.server.sessions.discard-storage-test
```
