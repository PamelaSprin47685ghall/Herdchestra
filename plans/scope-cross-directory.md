# Scope 跨目录接线清单

> 来源：`src/hc/core/scope` 子系统审查的 P1-5、P2-13、P2-14、P1-3（Owner `:source-index`）。
> 行号按当前工作树（2026-11-05）记录；其他工人改动后以函数名为准。
> 归属标签：`compose-owner` / `server-app` / `server-program-shell`。
> 本文件只是接线计划，不改源码，不做 git 操作。

## 0 总览

| # | 项 | 归属 | 目标 | 依赖 |
|---|---|---|---|---|
| 1 | 豁免/钉住 capability 注册（含 `negotiate` 决策） | server-program-shell | `src/hc/server/program/capability.clj` | 无 |
| 2 | `askPreviousScope` control handler、receipt、答案 steer | server-app（+ compose-owner 持久化） | `src/hc/server/app/system.clj`、Owner 扩展状态 | #5、Owner 持久化 |
| 3 | `assume` control handler | server-app | `src/hc/server/app/system.clj` | 无 |
| 4 | `negotiate` control handler（纯 book 快照） | server-app + server-program-shell | `src/hc/server/app/system.clj`、`capability.clj` | #1 |
| 5 | Owner synthetic 会话项，让成员名进入 Scope 投影 | compose-owner | `src/hc/compose/owner/handlers.clj`、`state.clj` | #2/#3/#4 |
| 6 | 第二次 reset 前先 ask 的准入门 | compose-owner | `src/hc/compose/owner/state.clj`、`handlers.clj` | #2、#5 |
| 7 | COMMON/PLAN 提示词补完整 Scope 规则 | server-app | `src/hc/server/app/prompts.clj` | 无 |
| 8 | Owner 投影拒收不得回退到未裁剪会话 | compose-owner | `src/hc/compose/owner/guards.clj` | 无 |
| 9 | Owner `:source-index` 补充 | compose-owner | `src/hc/compose/owner/state.clj` | 无 |
| 10 | （可选）Owner `:scope-turn-id`，精确识别同一模型回合 | compose-owner +（可选）scope 包 | `handlers.clj`、`state.clj`、`projection.clj` | #9 |

---

## 1. 豁免/钉住 capability 注册（server-program-shell）

**目标**：`src/hc/server/program/capability.clj:12-36`（`roles`）、`:42-78`（`program-functions`）、`:88-93`（`control-primitives`）。

**现状**：
- `:clj-review` 与 `:clj-worker` 已含 `:askPreviousScope :assume`；`:clj-plan` 与 `:dedicated-worker` 不含（符合根 §6.2“Plan 面上无此类豁免查询通道”）。
- `program-functions` 已有 `askPreviousScope` / `assume`。
- `control-primitives` 已有 `:askPreviousScope :assume`。
- `negotiate` 在任何集合里都不存在；scope 包的 `default-exempt-members` 仍保留该名。

**修改**（先做决策，再照做）：

1. 若 `negotiate` 是模型可调工具（推荐，因为根 §6.2 把它列为豁免工具，且 `scope/snapshot` 已存在）：
   - 在 `:clj-review` 与 `:clj-worker` 的 `:capabilities` 加 `:negotiate`；不要加到 `:clj-plan` 或 `:dedicated-worker`。
   - 在 `program-functions` 加 `negotiate :negotiate`。
   - 在 `control-primitives` 加 `:negotiate`。
2. 若 `negotiate` 是宿主内部操作、不是模型工具：
   - 不改以上三处；在根/包规格注明该名保留但未暴露。scope 包的豁免逻辑保持不变。

**代码形状**：

```clojure
;; roles
:clj-review
{:label "Reviewer"
 :capabilities #{:cat :stat :ls :where :whereAll :unpark :review
                 :askPreviousScope :assume :negotiate}}

:clj-worker
{:label "Swarm worker"
 :capabilities #{:cat :stat :ls :write :mkdir :rm :mv
                 :where :whereAll :park :unpark :call
                 :split :claim :finish
                 :cancel :replace :release :reopen :comment :update
                 :take-or-die :park-slot :abandon
                 :resolve-action :acknowledge-recovery
                 :askPreviousScope :assume :negotiate}}

;; program-functions
'{... askPreviousScope :askPreviousScope
      assume :assume
      negotiate :negotiate}

;; control-primitives
#{... :askPreviousScope :assume :negotiate}
```

**测试**：
- 更新 `test/hc/server/program/capability_test.clj:5-18` 的 `role-sets-match-the-spec-table` 精确集合；更新 `:20-25` 的 `narrowing-cannot-widen` 精确集合。
- 新增 `negotiate-is-a-reviewer-and-worker-capability-test`：Reviewer/Worker 含 `:negotiate`，Plan/dedicated-worker 不含；`(get cap/program-functions 'negotiate)` 为 `:negotiate`；`cap/control-primitives` 含 `:negotiate`。
- 若不采用 `negotiate` 工具，新增 `negotiate-is-reserved-and-not-a-capability-test`，锁定“未暴露”的决定。

**风险**：`cap/narrowed` 的精确集合测试会因新增 capability 失败；必须同步改测试，不能只改源码。

**归属**：`server-program-shell`。

---

## 2. `askPreviousScope` control handler（server-app）

**目标**：`src/hc/server/app/system.clj:883-923`（`control-fallback`）；调用点 `:1051-1052`、`:1092-1093`。

**现状**：
- `control-fallback` 只处理 `:devops` 与 `:review`；其余 primitive 返回 `:UNSUPPORTED_OPERATION`。
- `program/runtime.clj:1124-1150` 的 `do-control` 在 control 映射缺少 primitive 时抛 `:CAPABILITY_UNAVAILABLE`。
- 没有任何 `askPreviousScope` 的 receipt、持久化或答案 steer 实现；scope 包只暴露 `previous-scopes` / `preceding-scope`。

**修改**：

1. 给 `control-fallback` 增加第三个参数（或一个服务 map）：
   ```clojure
   (defn- control-fallback
     [owner-ref devops-exec-ref ask-previous-scope-ref]
     (fn [ctx {:keys [primitive request]}]
       (case primitive
         ...
         :askPreviousScope
         (if-let [ask @ask-previous-scope-ref]
           (ask {:session-id (or (:session-id ctx) (:session-id request))
                 :call-id (or (:call-id ctx) (:call-id request))
                 :tenure-id (:tenure-id ctx)
                 :questions (:questions request)})
           {:ok? false
            :error {:code :ASK_PREVIOUS_SCOPE_NOT_READY
                    :message "askPreviousScope needs a started relay"}}))))
   ```
2. 更新两个调用点（`:1051-1052`、`:1092-1093`）传入 ref；在 `system.clj` 的组件初始化里创建该 ref（或复用现有的 mission/relay 服务引用）。
3. 服务实现必须满足：
   - **目标 Scope**：`askPreviousScope` 的“前一个”是相对**将要打开的新 Scope**而言。作为第二次 reset 的前置 ask，目标是**当前 beat** `(last (ledger/reset-beats book))`，不是 `preceding-scope`。`preceding-scope` 只用于“已经在新 Scope 内、要问再前一个”的场景。实现时把这两个场景分开，避免取错。
   - **同步 receipt**：返回值只含“已受理”receipt，不含答案。receipt 形状建议：
     ```clojure
     {:ok? true
      :outcome :accepted
      :receipt {:v 1
                :session-id sid
                :call-id cid
                :previous-scope-call-id prev-call-id
                :questions questions
                :status :accepted
                :at now}}
     ```
   - **持久化 + 重放幂等**：以 `[session-id call-id]` 为键写入 Owner 的持久扩展状态（可复用 `mission/extensions-put` 的 `:ask-previous-scope` key，或新增 mission ledger）。同键重放返回同一 receipt，不重复入队 steer。
   - **答案 steer**：答案不在返回值里；随后以
     ```clojure
     {:role :user
      :attribution :agent
      :source :ask-previous-scope
      :call-id cid
      :text answer}
     ```
     到达。答案的合成属于 owner/mission 层；本项只要求入队形状与“不在 receipt 里”。
   - **没有前一个归档 Scope**：返回 `{:ok? false :error {:code :NO_PREVIOUS_SCOPE ...}}`（或规格选定的 `NoPreviousScope`）。注意：第一次 reset 之前没有前一个；第二次 reset 之前的前一个是当前 beat，存在。
4. `control-fallback` 的 docstring 更新：说明 `:askPreviousScope` 的 receipt/steer 归属与“不得把答案放进返回值”。

**测试**（`test/hc/server/app/control_port_test.clj`，沿用该文件的私有 var 访问方式）：
- `ask-previous-scope-returns-a-receipt-without-the-answer-test`：fake ask 服务返回 receipt + 记录 steer；断言 fallback 返回 `:outcome :accepted`，返回值不含 `:answer`/`:text`，steer 恰好一条且 `:attribution :agent`。
- `ask-previous-scope-without-a-previous-scope-is-explicit-test`：fake 返回 `:NO_PREVIOUS_SCOPE`；断言 fallback 原样返回该错误码。
- `ask-previous-scope-replay-is-idempotent-test`：同一 `[session-id call-id]` 调两次；断言两次 receipt 相同、steer 只有一条。
- `ask-previous-scope-without-a-service-is-explicit-test`：ref 为 nil；断言 `:ASK_PREVIOUS_SCOPE_NOT_READY`。

**归属**：`server-app`（handler + 服务引用）；receipt 的持久化若落在 Owner 扩展状态，由 `compose-owner` 提供 store/key。

---

## 3. `assume` control handler（server-app）

**目标**：`src/hc/server/app/system.clj:883-923`（`control-fallback`）。

**现状**：`:assume` 落到默认 `:UNSUPPORTED_OPERATION`。

**修改**：

```clojure
:assume
(let [assumption (:assumption request)]
  (if (and (string? assumption)
           (not (str/blank? assumption)))
    {:ok? true
     :outcome :assumed
     :text "已笃定，不再因为没有信息增量的犹豫反复改判。"}
    {:ok? false
     :error {:code :ASSUMPTION_INVALID
             :message "assume needs a non-blank :assumption"}}))
```

要求：
- 返回值**不回显** `assumption`。
- 固定 prose 与根 §6.2 完全一致。
- 不写 Scope 记录、不扣 retention；`assume` 的钉住由 #5 的 synthetic 会话项完成，retention 仍由外层调用的 `:scope` 结算。

**测试**（`test/hc/server/app/control_port_test.clj`）：
- `assume-returns-fixed-prose-and-never-echoes-test`：请求 `{:assumption "secret"}`；断言 `:text` 等于固定 prose，且结果/文本不含 `"secret"`。
- `assume-without-a-non-blank-assumption-is-explicit-test`：`{}`、`""`、`"  \n"`、非字符串 → `:ASSUMPTION_INVALID`。

**归属**：`server-app`。

---

## 4. `negotiate` control handler（server-app + server-program-shell）

**目标**：`src/hc/server/app/system.clj:883-923`；capability 注册见 #1。

**现状**：无 handler；`scope/snapshot` 只适用于有状态 `Scope`，Owner 用的是纯 `{:book :records}`，不能直接调用。

**修改**：在 `control-fallback` 增加：

```clojure
:negotiate
(if-let [owner @owner-ref]
  (let [scope-state (get-in (:state ((:snapshot owner))) [:scope])
        book (:book scope-state)
        reset (scope-ledger/last-reset book)
        resets (scope-ledger/reset-beats book)]
    {:ok? true
     :kind :negotiate
     :goal (:goal reset)
     :budget (:budget reset)
     :remaining (scope-ledger/remaining book)
     :established? (scope-ledger/established? book)
     :must-reset (or (not (scope-ledger/established? book))
                     (scope-ledger/spent? book))
     :spin (scope-ledger/spin book)
     :resets (count resets)
     :notice (when-not (scope-ledger/established? book)
               (scope-records/cold-start-notice {:resets resets}))})
  {:ok? false
   :error {:code :OWNER_NOT_READY
           :message "negotiate needs a started Owner"}})
```

- 在 `system.clj` 的 ns 增加 `[hc.core.scope.ledger :as scope-ledger]` 与 `[hc.core.scope.records :as scope-records]`（或只 require `hc.core.scope` 并用其导出）。
- 不要调用 `hc.core.scope/snapshot`：它接收有状态 `Scope` record，Owner 没有。
- 返回值形状与 `scope/snapshot` 保持一致：`goal/budget/remaining/established?/must-reset/spin/resets/notice`。
- 若 #1 决定 `negotiate` 不暴露，则本项跳过，并在规格注明 `snapshot` 仍是未接线的 seam。

**测试**（`test/hc/server/app/control_port_test.clj`）：
- `negotiate-control-returns-the-owner-scope-snapshot-test`：fake owner 的 snapshot 返回 `{:state {:scope {:book book}}}`，book 由 `ledger/settle-turn` 造一个已 reset 的 book；断言 `:goal/:budget/:remaining/:spin/:resets/:must-reset`。
- `negotiate-control-without-owner-is-explicit-test`：owner ref nil → `:OWNER_NOT_READY`。
- 冷启 book（未 established）→ `:must-reset true` 且 `:notice` 的 `:kind :scope-cold-start`。

**归属**：`server-app`（handler/require）；`server-program-shell`（capability 注册与测试）。

---

## 5. Owner synthetic 会话项（compose-owner）

**目标**：`src/hc/compose/owner/handlers.clj:1707-1723`（`apply-call-terminal`）或 `:1575-1582`（tool-call 入会话处）；`src/hc/compose/owner/state.clj:1070-1097`（`message->scope-item`）。

**现状**：
- Owner 的 `member` 是顶层 `clj-plan`/`clj-review`/`clj-worker`（`handlers.clj:1446-1448`）。
- 程序内的 `assume` / `askPreviousScope` / `negotiate` 只出现在程序 receipt 的 `:control-results`（`program/runtime.clj:1124-1150` 写入，`program-view` 暴露），不会成为 `:conversation` 的 `:tool-call`/`:tool` 项。
- 因此 scope 投影的 `pinned?` / 豁免分支永远看不到这些成员名；`assume` 的“跨 reset 保留”和 `askPreviousScope` 的“调用与结果保留”在生产中不生效。

**修改**：

1. 在程序终态进入 Owner 时读取 `(get-in message [:payload :control-results])`。`apply-call-terminal` 目前只 `append-terminal`；在它返回前追加 synthetic 对：
   ```clojure
   (def ^:private scope-control-primitives
     #{:assume :askPreviousScope :negotiate})

   (defn- append-scope-control-items
     [state message ctx]
     (let [call-id (:call-id message)
           results (get-in message [:payload :control-results])]
       (reduce
        (fn [state {:keys [primitive result]} index]
          (if (and (contains? scope-control-primitives primitive)
                   (true? (:ok? result)))
            (let [inner-id (str call-id ":scope:" (name primitive) ":" index)
                  member (name primitive)
                  text (or (:text result)
                           (some-> (:receipt result) pr-str)
                           (pr-str result))]
              (-> state
                  (update :conversation conj
                          {:role :tool-call
                           :member member
                           :call-id inner-id
                           :at (:now ctx)})
                  (update :conversation conj
                          {:role :tool
                           :member member
                           :call-id inner-id
                           :text text
                           :at (:now ctx)})))
            state))
        state
        results
        (range))))
   ```
2. 在 `apply-call-terminal` 的 `:state` 上调用它（只对 `:program-runtime` 的 `clj-*` 调用；`message` 的 `:source` 为 `:program-runtime`）：
   ```clojure
   (let [state (if (= :program-runtime (:source message))
                 (append-scope-control-items state message ctx)
                 state)]
     {:state (append-terminal state (dissoc message :type))
      :receipt {:status :accepted :outcome :terminal-queued}})
   ```
3. **不要**为这些 synthetic 项创建 `:calls` 记录、不要调用 `settle-tool-scope`、不要给它们 `:scope` 注解。它们是 conversation-only，和现有 admission-refusal 的 synthetic 对同性质。
4. 使用确定性 `inner-id`（含 primitive 与 result 索引），重放/重试时同一对不会重复追加；如果 `:control-results` 可能重复出现，追加前用 `(some #(= inner-id (:call-id %)) (:conversation state))` 去重。
5. `state/message->scope-item` 已把 `:tool-call`/`:tool` 映射成带 `:member`/`:call-id` 的 Scope 项，无需改动；projection 的 `pinned?` 会按成员名保留。`askPreviousScope`/`negotiate` 在 `default-pinned-members`，`assume` 也在。

**测试**：
- `test/hc/compose/owner/handlers_test.clj` 或 `scope_state_test.clj`：
  - `program-control-results-become-scope-conversation-items-test`：构造一个 `:call/terminal`，payload 含 `:control-results [{:primitive :assume :result {:ok? true :text "..."}}]`；断言 conversation 末尾有一对 `:tool-call`/`:tool`，member `"assume"`，call-id 以 `:scope:assume:` 开头；断言 `:calls` 没有新记录。
  - `scope-projection-pins-assume-across-a-reset-test`：先放 assume synthetic 对，再放 reset 调用/结果；`state/project-conversation` 后 assume 对仍在。
  - `scope-projection-pins-ask-previous-scope-and-negotiate-test`：同上，覆盖两个成员名。
  - `scope-control-items-are-idempotent-test`：同一终态重放两次，conversation 不重复。
- 如果 `apply-call-terminal` 不方便直接测，可把 `append-scope-control-items` 设为 private 并用 `(var-get #'handlers/append-scope-control-items)` 单测。

**风险**：
- 这些 synthetic 项会进入模型出站副本（assistant/tool 消息）。`assume` 的固定 prose、ask 的 receipt、negotiate 的 snapshot 都应是模型可读的简短文本；不要把整个大 map 塞进 `:text`，否则上下文膨胀。建议对 `askPreviousScope`/`negotiate` 用 receipt 摘要，而不是完整 snapshot。
- 不要把这些项计入 Pair Hint 的 `:chain` 之外的 Scope 结算；它们没有 `:scope`，`settle-tool-scope` 会因 `:scope-annotation nil` 跳过。
- 若程序终态 payload 未带 `:control-results`，先在 `src/hc/server/program/runtime.clj` 的 `program-view`/terminal 结果里带上（server-program-shell），否则 Owner 侧拿不到 primitive 结果。

**归属**：`compose-owner`。

---

## 6. 第二次 reset 前先 ask 的准入门（compose-owner）

**目标**：`src/hc/compose/owner/state.clj:1003-1009`（`scope-admission-check`）；`handlers.clj:1477`（调用点）；ask receipt 的持久化键（#2）。

**现状**：`scope-admission-check` 只调用 `scope-admission/check`，不知道 ask receipt；第二次 reset 不会被拦。

**修改**：

1. 在 Owner 的持久状态里存 ask receipt，键为 `[session-id previous-scope-call-id]`（或直接以 `previous-scope-call-id` 为键，session 已隔离）。#2 的 ask handler 写入；重放幂等。receipt 持久化用于“同一 call-id 重放返回同一受理”，不是为了让旧 beat 的 receipt 满足新 beat 的 ask 门；冷启后第一次 reset 因 `established? false` 直接放行，gate 只查当前 beat 的 receipt。若 gate 用 `[:scope :asks]` 缓存，`state/restore` 需按需 hydrate；也可以直接查扩展 store。
2. 在 `scope-admission-check` 里，先做 ask 门，再做 scope 准入：
   ```clojure
   (defn scope-admission-check
     [state member annotation]
     (let [book (scope-book state)
           reset? (= :reset-scope (:kind annotation))
           ;; 只有本进程已经确证过预算，才谈得上“第二次 reset”。
           ;; 冷启后的第一次 reset 仍按 §3.5 直接放行。
           established? (scope-ledger/established? book)
           beats (scope-ledger/reset-beats book)
           ;; 第二次及以后：当前 beat 就是即将被替换的 Scope。
           current-beat (peek beats)
           asked? (contains? (get-in state [:scope :asks])
                             (:call-id current-beat))]
       (cond
         (and reset? established? (seq beats) (not asked?))
         {:ok false
          :code :SCOPE_ASK_REQUIRED
          :message "the second and later resets must ask the current Scope first"
          :details {:previous-scope-call-id (:call-id current-beat)}
          :phase :admission}

         :else
         (scope-admission/check
          {:member member
           :annotation annotation
           :retention (scope-ledger/retention book)
           :admission (scope-admission/admission)}))))
   ```
3. 语义要点：
   - **冷启后的第一次 reset**：`established?` 为 false，即使 `beats` 里有持久历史也不要求 ask；否则冷启会被 ask 门卡死，违反 §3.5。
   - **本进程第一次 reset**：`established?` 为 false，不要求 ask；它结算后预算建立。
   - **本进程第二次 reset**：`established?` 为 true，`beats` 的最后一个就是当前 Scope；ask 的目标是它。不要用 `preceding-scope`（它返回的是再前一个，不是即将被替换的当前 beat）。
   - **本进程第三次 reset**：当前 beat 是第二个；ask 的目标是第二个。`preceding-scope` 仍然不是这个目标。
   - `askPreviousScope` 与 reset **不得同一次调用**；scope 包的 exempt 短路已经保证带 reset 注解的 ask 不开新 Scope，但 ask 门要求 receipt 在 reset 之前已经存在。
4. 若不想把规则放进 Owner，可放到 relay/ask 服务层；但必须在 reset 准入前拒绝，不能只在提示词里要求。`scope` 包按 §5 不拥有此规则。

**测试**（`test/hc/compose/owner/scope_state_test.clj`）：
- `second-reset-without-an-ask-is-refused-test`：book 已有一个 reset；`scope-admission-check` 对 reset 注解返回 `:SCOPE_ASK_REQUIRED`，且 `:details :previous-scope-call-id` 指向第一个 reset 的 call-id。
- `first-reset-needs-no-ask-test`：空 book 的 reset 通过；冷启 book（有持久 reset 历史但 `:retention :established? false`）的第一次 reset 也通过。
- `second-reset-with-an-ask-receipt-is-admitted-test`：在 `[:scope :asks]` 放入第一个 call-id 的 receipt；reset 通过。
- `third-reset-needs-the-second-beats-receipt-test`：两个 reset 后，只有第一个 receipt 时拒绝；放入第二个 receipt 后通过。
- `ask-and-reset-in-one-call-does-not-open-a-scope-test`：`askPreviousScope` 带 reset 注解，走 exempt 分支，book 不变（scope 包已有行为；Owner 层补一条端到端）。

**归属**：`compose-owner`（admission 门 + receipt 读取）；`server-app`（#2 写入 receipt）。

---

## 7. COMMON/PLAN 提示词补完整 Scope 规则（server-app）

**目标**：`src/hc/server/app/prompts.clj:39-47`（`common` 的 `# Scope`）；`:110-118`（`plan-prompt` 的 `# Scope`）。

**现状**：两段都只有四种 kind、单真注解、全 me-too 默认、budget 0、冷启 reset；没有豁免/钉住名单、没有第二次 reset 前 ask、没有准入 vs 结算分类。两段文本完全相同，PLAN 不是“紧凑版”。

**修改**：

1. `common` 的 `# Scope` 段在现有文本后补：
   ```
   Exempt: negotiate and askPreviousScope spend no retention and are callable
   at budget 0; their calls and paired results survive every reset. assume is
   pinned but not exempt: keep-last on assume still spends 1. The first reset
   needs no ask; the second and later resets must be preceded by
   askPreviousScope, never in the same call. Admission refusals happen before
   the tool runs; settlement refusals (record/turn) happen after and the tool
   effect stands.
   ```
   中文版（若提示词保持中文）等价表述；关键是六个事实：豁免两名、assume 钉住但不豁免、第一次 reset 不 ask、第二次起先 ask、ask 与 reset 不同调用、准入/结算分类。
2. `plan-prompt` 的 `# Scope` 保持更短，只保留：
   - 四种 kind 与字段；
   - 一回合一条真注解、全 me-too 默认 keep-last；
   - budget 0 只放行 reset、冷启先 reset；
   - 第二次 reset 前先 ask（Plan 没有 askPreviousScope capability，这条对 Plan 是“不要尝试”，或按产品决定省略）。
   不要把 `common` 的完整豁免段复制进 Plan；Plan 没有这些 capability。
3. 确保同一角色的提示词只出现一次 Scope 段：Plan 用 `plan-prompt`；Reviewer/Worker 用 `common`（`prompts.clj:221-223`）。不要在 `reviewer-prompt`/`swarm-worker-prompt` 再重复。

**测试**（`test/hc/server/app/prompts_test.clj`，已有 `prompt-includes?` helper）：
- `common-prompt-states-the-exempt-and-ask-rules-test`：对 `prompts/common` 断言包含 `"negotiate"`、`"askPreviousScope"`、`"assume"`、`"budget 0"`、`"second"`/`"ask"`、`"admission"`、`"settlement"`。
- `plan-prompt-keeps-the-compact-scope-rules-test`：对 `prompts/plan-prompt` 断言包含四种 kind 与 `"budget 0"`；断言不包含 `"negotiate"`（Plan 无此 capability），除非产品决定在 Plan 里提一句“不可用”。
- `scope-rules-appear-once-per-role-test`：decorate 一个 `:clj-review` 请求，断言 system 消息里 `"# Scope"` 只出现一次；`:clj-plan` 同理。

**归属**：`server-app`。

---

## 8. Owner 投影拒收不得回退到未裁剪会话（compose-owner）

**目标**：`src/hc/compose/owner/guards.clj:1626-1635`（`conversation-tail`）；调用点 `:1811`、`:1927`。

**现状**：
```clojure
(defn- conversation-tail [state]
  (let [result (state/project-conversation state (:conversation state))]
    (if (:ok? result)
      (vec (take-last 32 (:messages result)))
      ;; fallback: unprojected conversation
      (let [items (scope-runtime/conversation->items (:conversation state))]
        (vec (take-last 32 (scope-runtime/items->messages items)))))))
```
投影拒收（`:SCOPE_FIELD_INVALID`）时，回退把**未裁剪**的会话发给模型；reset 边界之前的旧工具调用/结果会复活，违反根 §6.2。

**修改**：

1. 让 `conversation-tail` 返回显式结果：
   ```clojure
   (defn- conversation-tail
     [state]
     (let [result (state/project-conversation state (:conversation state))]
       (if (:ok? result)
         {:ok? true :messages (vec (take-last 32 (:messages result)))}
         {:ok? false :error (:error result)})))
   ```
2. 增加一个 helper，供两个调用点使用：
   ```clojure
   (defn- require-conversation-tail
     [state]
     (let [{:keys [ok? messages error]} (conversation-tail state)]
       (if ok?
         messages
         (throw (ex-info "Scope projection failed"
                         {:type ::scope-projection-failed
                          :error error})))))
   ```
3. 两个调用点把 `(conversation-tail state)` 换成 `(require-conversation-tail state)`。确认 `scheduler/guard` 的 apply 异常路径会记录失败/阻塞而不是静默继续；若不会，在调用点包 `try/catch`，写 `:SCOPE_PROJECTION_FAILED` 诊断并拒绝本次模型请求。**绝不**在拒收时发送未投影会话。
4. 如果可用性要求降级，只能：
   - 修复导致冲突的相邻 call-id 对后重新投影；或
   - 用同一 reset 边界重新构造 items 后再投影；
   不能绕过边界。
5. `take-last 32` 是 recent-window cap，不是 Scope 裁剪的替代；保留，但要在注释里说明它发生在投影之后。

**测试**：
- `test/hc/compose/owner/conversation_stability_test.clj` 或 `handlers_test.clj`：
  - `projection-refusal-does-not-send-the-unprojected-conversation-test`：构造一个相邻 tool-call/tool-result，call-id 不兼容且保留决定不同，使 `state/project-conversation` 返回 `:SCOPE_FIELD_INVALID`；调用 `(var-get #'guards/conversation-tail)`（或 `require-conversation-tail`）；断言返回 `{:ok? false}`/抛出 `::scope-projection-failed`，且没有旧 tool 结果进入任何 `:messages`。
  - `projection-success-still-caps-to-32-messages-test`：正常投影仍 `take-last 32`。
- 若 `scheduler/guard` 的异常处理不在本测试范围，至少直接测 helper 的返回值，锁定“不回退”。

**归属**：`compose-owner`。

---

## 9. Owner `:source-index` 补充（compose-owner）

**目标**：`src/hc/compose/owner/state.clj:1131-1145`（`project-conversation`）；`message->scope-item` 在 `:1070-1097`。

**现状**：
```clojure
items (mapv message->scope-item messages)
```
Owner 的 items 没有 `:source-index`；projection 的 `same-source-turn?` 在 Owner 路径永远 false。removeLast 的当前回合正文/兄弟调用排除依赖“上一个 tool-result”搜索，通常够用；但 item 形状与 runtime 适配器不一致，且未来需要按消息组排除时缺字段。

**修改**：

```clojure
(let [book (scope-book state)
      items (mapv (fn [index message]
                    (assoc (message->scope-item message) :source-index index))
                  (range)
                  messages)
      result (scope-projection/project
              items
              {:settled? true
               :reset-call-id (scope-ledger/last-reset-call-id book)
               :erase (scope-ledger/erases book)})]
  ...)
```

- `scope-item->message` 已忽略 `:source-index`，无需改。
- Owner 每条 conversation message 产生一个 item；索引是消息位置，不是模型回合身份。它让 item 形状与 `runtime/message->items` 对齐，但**不能**单独区分“同一模型回合的正文与工具调用”（它们在 Owner 里是不同消息、不同索引）。若要精确识别，见 #10。
- 如果 `message->scope-item` 以后对一条消息产生多个 item（例如把 text/thinking/tool-calls 合并在一条），`mapv` 需要改成 `mapcat` 并为每个 item 分配同一 index；当前是一条消息一个 item，`mapv` 足够。

**测试**：
- `test/hc/compose/owner/handlers_test.clj` 或 `assistant_prefix_test.clj`：
  - `project-conversation-tags-items-with-source-index-test`：用 `with-redefs` 捕获 `scope-projection/project` 的 `items` 参数：
    ```clojure
    (let [captured (atom nil)]
      (with-redefs [scope-projection/project
                    (fn [items _]
                      (reset! captured items)
                      items)]
        (state/project-conversation state conversation))
      (is (= (range) (map :source-index @captured))))
    ```
    需要在该测试 ns require `[hc.core.scope.projection :as scope-projection]`。
  - `remove-last-keeps-current-prose-and-replaces-previous-round-test`：conversation 为上一轮 tool-call/result、当前 assistant 正文、removeLast 调用；book 里有对应 `:remove` 记录；断言投影后上一轮被替换、当前正文保留。这个测试主要验证 #9 + 包内 beat 选择，不依赖 `:source-index` 的 same-turn 分支。
- 若不想用 `with-redefs`，可把 `message->scope-item` 改成接收 index 的纯函数并直接单测；但它是 private，测试仍需 var 访问。

**归属**：`compose-owner`。

---

## 10. （可选）Owner `:scope-turn-id`，精确识别同一模型回合（compose-owner）

**目标**：`src/hc/compose/owner/handlers.clj:1575-1582`（tool-call 入会话）；`state.clj:1070-1097`（`message->scope-item`）；`src/hc/core/scope/projection.clj` 的 `same-source-turn?`（scope 包，可选改动）。

**现状**：Owner 的 assistant 正文消息带 `:run-id`/`:call-id`（模型 call id），但 tool-call 会话项只有 `:member`/`:call-id`/`:at`，没有模型回合 id。`same-source-turn?` 只比较 `:source-index`，无法把“当前正文”和“当前 removeLast 调用”识别为同一回合。

**修改**：

1. 在 `handlers.clj:1575-1582` 的 tool-call 会话项加 `:scope-turn-id model-call-id`（与 call 记录的 `:scope-turn-id` 同值）。
2. 在 `state/message->scope-item` 的 `:tool-call` 分支带 `:scope-turn-id (:scope-turn-id message)`；assistant 消息的 `:scope-turn-id` 可用其 `:call-id`（模型 call id）或 `:run-id`，需与 tool-call 的值同域。
3. 在 `projection/same-source-turn?` 增加：
   ```clojure
   (or (and (some? (:source-index left))
            (some? (:source-index right))
            (= (:source-index left) (:source-index right)))
       (and (some? (:scope-turn-id left))
            (some? (:scope-turn-id right))
            (= (:scope-turn-id left) (:scope-turn-id right))))
   ```
   这是 scope 包改动；如果不想改包，Owner 侧只能靠“上一个 tool-result”搜索，当前常见形状已够。
4. 注意：assistant 消息的 `:call-id` 是模型 call id，tool-call 会话项的 `:call-id` 是物理工具 call id；不要混用。用单独的 `:scope-turn-id` 字段，避免与物理 call-id 冲突。

**测试**：
- `remove-last-skips-current-prose-by-scope-turn-id-test`：当前 assistant 正文与 eraser 的 `:scope-turn-id` 相同；断言当前正文不被替换，上一轮被替换。
- `remove-last-does-not-skip-a-different-turn-test`：不同 `:scope-turn-id` 的正文仍按规则处理。

**归属**：`compose-owner`（字段生产）；scope 包的 `same-source-turn?` 改动属于 scope 维护者，若采用需同步 `test/hc/core/scope_test.clj`。

---

## 附录 A：测试矩阵

按归属运行：

```bash
# scope 包（当前已通过：93 tests / 382 assertions / 0 failures / 0 errors）
clojure -M:dev -m hc.core.scope.run-tests
clojure -M:test -n hc.core.scope-test

# server-program-shell
clojure -M:test -n hc.server.program.capability-test
clojure -M:test -n hc.server.program.runtime-test

# server-app
clojure -M:test -n hc.server.app.control-port-test
clojure -M:test -n hc.server.app.prompts-test
clojure -M:test -n hc.server.app.system-test

# compose-owner
clojure -M:test -n hc.compose.owner.scope-state-test
clojure -M:test -n hc.compose.owner.handlers-test
clojure -M:test -n hc.compose.owner.conversation-stability-test
clojure -M:test -n hc.compose.owner.assistant-prefix-test
```

建议的跨目录端到端测试（新文件或现有 integration test）：
- `test/hc/compose/owner/scope_control_integration_test.clj`：
  1. 程序调用 `assume` → 会话出现 synthetic 对 → reset → 投影仍保留 assume 对。
  2. 第二次 reset 未 ask → `:SCOPE_ASK_REQUIRED`；ask receipt 后 → 通过。
  3. `askPreviousScope` 带 reset 注解 → 不开新 Scope，receipt 不入 Scope 记录。
  4. `negotiate` 返回的 snapshot 与 Owner book 的 goal/budget/remaining/spin/resets 一致。
- `test/hc/server/app/scope_control_port_integration_test.clj`：
  1. control-fallback 的 assume/ask/negotiate 三个分支与错误码。
  2. ask 重放幂等与“答案不在返回值”。
  3. negotiate 冷启 book 的 `:must-reset`/`:notice`。

## 附录 B：顺序与依赖

1. 先做 #1（capability 决策）与 #3（assume）、#4（negotiate）——它们不依赖 Owner 会话形状。
2. 再做 #2（ask 服务/receipt）与 #6（ask 门）——需要 receipt 存储；两者可以同一个 owner 完成。
3. 再做 #5（synthetic 会话项）——依赖 #2/#3/#4 的 control 结果形状。
4. #9 与 #10 是 Owner 投影精度，可与 #5 并行；#10 的包内改动最后做，避免与 scope 包其他改动冲突。
5. #7（提示词）与 #8（fallback）独立，可并行。
6. 每项先跑该目录的聚焦测试，再由主代理跑一次全量；不要在每个子项中途跑全量。

## 附录 C：不要做的事

- 不要把 `assume` / `askPreviousScope` / `negotiate` 注册成 Plan 的 capability（根 §6.2：Plan 面无此类豁免查询通道）。
- 不要把 `askPreviousScope` 的答案放进同步返回值；只能是 receipt + 后续 `role:"user"`、`attribution:agent` 的 steer。
- 不要在 reset 与 ask 的同一次调用里同时提交两者；scope 包的 exempt 短路会忽略 reset，但 ask 门要求 receipt 先存在。
- 不要把 `preceding-scope` 当作“第二次 reset 前要问的那个 Scope”；pre-reset 的目标是当前 beat `(last reset-beats)`。
- 不要在投影拒收时发送未裁剪会话；不要把 `take-last 32` 当成 Scope 裁剪。
- 不要用物理工具 call-id 充当模型回合 id；Owner 已有 `:scope-turn-id` 字段，复用它。
