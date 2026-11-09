# `hc.compose.steward` — repository Steward 的纯状态组合层

Status: implemented.

Authority: root `AGENTS.md` §2.2（仓库守望层：`Steward` 与 `Standing Sphinx`）
与 §8.2 > `src/AGENTS.md` > `src/hc/compose/AGENTS.md` > 本文件。本文件只固定
局部契约，不新增产品义务。

## 1 边界

`hc.compose.steward` 组合一个仓库级 Steward 的状态值：

```clojure
{:schema-version 1
 :steward-id "steward"
 :intent {:revision 0 :text "" :received-at 0}
 :capacity {:slots-total 4 :slots-allocated 0 :min-spare 1 :missions []}
 :cognition ...        ; hc.core.steward-sphinx cognition
 :frontier ...         ; Decision Frontier 游标
 :docket ...           ; Mission Docket
 :links {...}          ; mission-id -> runtime linkage
 :last-observation ...}
```

所有公开函数都是纯函数：不读存储、不读时钟、不启动线程、不调用 Git。调用方
提供 `now`，并把返回的 state 提交到自己的 Store。本层只调用
`hc.core.steward-sphinx` facade，不 require `hc.server.*` 或
`hc.client.*`；持久化由 `hc.server.app.steward-store` 适配，运行循环由
`hc.compose.supervisor` 承载。

## 2 状态与观测

```clojure
(steward/initial-state)
(steward/initial-state {:steward-id "repo"})
;; => 空 intent 的 Steward；evaluate-frontier 在真实 operator intent 到达前拒绝

(steward/observe-world state {:now epoch-ms
                              :intent {...}
                              :capacity {...}
                              :git-commits [...]})
;; => {:ok? true :state state'} 或 {:ok? false :state 原 state :error {...}}
```

`observe-world` 是一次原子观测：intent 或 capacity 校验失败、或任一 Git
evidence 形状错误，都返回输入 state，不保留同批已摄入的前几条 evidence。
`:last-observation` 记录 `:at`、Git commit 数、intent revision、最新
evidence id 与 capacity signature，不复制完整 evidence。

`fleet-capacity` 把 fleet 行映射为 capacity 观测：`:running` 占槽，
`:queued` 是可吸收的已准入工作，`:joined`/`:abandoned`/`:failed` 视为结束；
未知状态的行被忽略。`slots-total` 默认 4、`min-spare` 默认 1，且总数永不
低于运行数。fleet 状态到 Mission 状态的映射固定在
`fleet-status->mission-status`（`:running → :active`、`:queued → :admitted`、
`:joined → :completed`、`:abandoned → :abandoned`、`:failed → :failed`）。

## 3 认识、前沿与 Docket

```clojure
(steward/ingest-git-evidence state evidence)
(steward/revise-cognition state revision)
(steward/evaluate-frontier state)
;; => {:ok? true :state state' :triggered? bool :frontier ... :reasons [...]}

(steward/decide state {:request-id "..." :candidates [...] :policy {...}})
(steward/link-mission state mission-id link-map)
(steward/transition-mission state mission-id next-status reason)
(steward/snapshot state)
```

1. `ingest-git-evidence` 对重复 evidence 幂等；冲突时 state 不变并返回 core
   拒绝。
2. `revise-cognition` 接受新的 thought frontier、assumptions 与 claims；
   没有新 evidence 的修订被 Sphinx 拒绝。
3. `evaluate-frontier` 无论是否触发都推进游标，因此同一世界不会重复触发；
   `:triggered?` 只在真正发出 frontier 时为 true。
4. `decide` 按 `request-id` 幂等重放；已准入但尚未链接的 mission 获得
   `:admitted` 链接，已有链接不被覆盖。
5. `link-mission` 不覆盖 Docket 的 `:mission-id`/`:status`；状态迁移只用
   `transition-mission`，迁移规则留在 core。
6. `snapshot` 是有界视图：不包含完整 cognition、frontier 游标、原始 evidence
   或 Docket 载荷，只保留 Current Best Answer、frontier 摘要、Docket 摘要、
   links 与最近观测。

## 4 失败形状

失败一律 `{:ok? false :state <输入 state> :error {:code :message :details?}}`。
拒绝码来自 `hc.core.steward-sphinx` 或本层的 `:INVALID_OBSERVATION`、
`:INVALID_REQUEST`、`:UNKNOWN_MISSION`、`:INVALID_LINK`。本层不把失败改写成
成功，也不吞掉 core 的 `:details`。

## 5 测试

`test/hc/compose/steward_test.clj` 覆盖 initial state、观测原子性、重复
evidence 幂等、frontier 游标推进、request-id 重放、mission 链接不被覆盖、
状态迁移镜像与有界 snapshot。纯状态测试不需要存储、模型或真实 Git。

## 6 契约完备性（0.0.3）

### (a) 公开 API

```clojure
(initial-state)                ; => state
(initial-state {:steward-id ..}) ; => state
(observe-world state observation) ; => {:ok? true :state ..}
                                  ; |  {:ok? false :state state :error {..}}
(ingest-git-evidence state evidence) ; => {:ok? true :state .. :evidence ..}
(revise-cognition state revision)    ; => {:ok? true :state ..}
(evaluate-frontier state)            ; => {:ok? true :state .. :triggered? bool
                                     ;     :frontier .. :reasons [..]}
(decide state request)               ; => {:ok? true :state .. :decision ..}
(link-mission state mission-id link-map) ; => {:ok? true :state .. :link ..}
(transition-mission state mission-id next-status reason)
;; => {:ok? true :state .. :mission .. :link ..}
(fleet-capacity rows) / (fleet-capacity rows opts) ; => capacity map
(snapshot state) ; => bounded view
```

所有成功路径返回 `{:ok? true ...}`（`snapshot`/`fleet-capacity` 是纯视图）；
所有失败路径返回 `{:ok? false :state <输入 state> :error {:code :message :details?}}`。

### (b) 失败/拒绝码

| code | 触发 |
|---|---|
| `:INVALID_OBSERVATION` | `observe-world` 的 observation 不是 map，或 `:git-commits` 不是序列 |
| `:INVALID_REQUEST` | `decide` 的 request 不是 map |
| `:UNKNOWN_MISSION` | `link-mission`/`transition-mission` 的 mission-id 不在 Docket |
| `:INVALID_LINK` | `link-mission` 的 link-map 不是 map |
| `:INVALID_EVIDENCE` / `:INVALID_INTENT` / `:INVALID_CAPACITY` | 透传 `hc.core.steward-sphinx` 的 evidence/intent/capacity 校验拒绝 |
| `:INVALID_ASSUMPTION` / `:INVALID_CLAIM` / `:INVALID_POLICY` / `:INVALID_CANDIDATE` / `:NO_NEW_EVIDENCE` / `:INVALID_COGNITION` | 透传 `hc.core.steward-sphinx` 的 cognition/frontier/docket 拒绝 |

本层不新增、不改写 core 的 code；完整触发条件见
`src/hc/core/steward_sphinx/AGENTS.md`。

### (c) 不变量

保证：纯函数；一次观测整批原子；frontier 游标只前进；同一 `request-id`
重放返回原 receipt；已存在的 mission link 不被覆盖；snapshot 不含完整
cognition/evidence/Docket 载荷。

不保证：不执行 effect、不提交 Store、不读时钟/Git/模型；不做跨 session
事务；不保证宿主按 fingerprint 去重（去重是 runtime 的责任）；不保证模型
输出质量或 Docket 决策正确；不保证进程崩溃后的状态恢复（由 Store 适配器
决定）。

### (d) 验证

```bash
clojure -M:test:compose -n hc.compose.steward-test
```

代码事实，未复测：`11 deftest + 8 defspec`（19 个测试入口）/ `155` 个
`is`；属性测试的实际 assertion 数随迭代次数变化。
