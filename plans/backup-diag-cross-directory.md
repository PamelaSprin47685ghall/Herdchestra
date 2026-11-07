# 备份/诊断/语音跨目录剩余需求

范围：本文件只记录本轮修复后仍跨出已改目录、需要其他 owner 落地的三项。每项含文件:行、现状、修改、测试与归属。本文档不修改源码。

归属总览：

| 项 | 归属 | 影响文件 |
|---|---|---|
| 1. 转写插入空 session guard | **client-workbench** | `src/hc/client/workbench/views/session.cljs` |
| 2. 诊断日志材料按 id 合并 | **server-runtime-ext** | `src/hc/server/diagnostics/service.clj` |
| 3. 消息翻译替换选区接口 | **client-conversation**（主）、client-workbench（草稿 mutation） | `src/hc/client/conversation/view.cljs`、`src/hc/client/workbench/views/session.cljs`、`src/hc/client/speech/ui.cljs` |

---

## 1. `insert-transcript!` 空 `session-id` guard

**归属：client-workbench**

### 文件与现状

`src/hc/client/workbench/views/session.cljs:307-308`：

```clojure
:insert-transcript! (fn [{:keys [session-id text insert-at]}]
                      (when (and (string? text) (not (str/blank? text)))
                        ...))
```

`hc.client.speech.ui/insert-transcript!` 现在已通过 `input_model/insert-payload` 传入 `:session-id`。但若调用方漏传或传入空值，当前回调仍会：

- 用 `nil` 作为 `draft/draft` 的键；
- 计算 `at` 时把 `nil` 会话的空草稿当作目标；
- 通过 `conversation-bridge/edit-draft!` 写入 `[:ui/draft nil]`，当前会话草稿不变，用户却看到录音状态被清空。

### 修改

只加 session 前置条件，不改插入算法：

```diff
                    :insert-transcript! (fn [{:keys [session-id text insert-at]}]
-                                         (when (and (string? text) (not (str/blank? text)))
+                                         (when (and (string? session-id)
+                                                    (not (str/blank? session-id))
+                                                    (string? text)
+                                                    (not (str/blank? text)))
                                            (let [db (application/current-state this)
                                                  d (draft/draft (:ui/draft db) session-id)
```

其余保持：`insert-at` clamp、`cursor` 前移、`edit-draft!` 调用。

### 测试

在 `test/hc/client/workbench/session_command_test.cljs` 或新增纯 helper 测试中覆盖：

1. 合法 `session-id` + 文本 → 只修改该 session 的 `:ui/draft`，光标为 `insert-at + count(text)`。
2. `session-id` 为 nil / 空白 → 不调用 `edit-draft!`，不产生 `[:ui/draft nil]`，草稿与录音 UI 均不被清空。
3. `text` 为空白 → 同样不写草稿。

若直接测试 defsc 回调成本高，可把“计算插入变更”抽成纯函数（如 `insert-transcript-change`）并单测；回调只负责调用 `conversation-bridge/edit-draft!`。

### 验收

- 空 session 不产生任何草稿键。
- 正常 session 的插入行为与本轮 speech 修复一致。

---

## 2. 诊断日志材料按 id 合并 `include?`

**归属：server-runtime-ext**

### 文件与现状

`src/hc/server/diagnostics/service.clj:215-223`：

```clojure
(defn- log-material
  [snap]
  (when (seq (:log-entries snap))
    {:id :detailed-log
     :title "Detailed request log"
     :purpose "Show request metadata and redacted parameters"
     :kind :log
     :include? false
     :content (:log-entries snap)}))
```

`src/hc/server/diagnostics/service.clj:250-266`：

```clojure
materials (cond-> (vec (:extra-materials request))
            (and (= :ok (:status snap)) (log-material snap))
            (conj (log-material snap)))
```

现状问题：

- 客户端已从服务端 `:extra-materials` 复制完整 `id/title/kind/purpose`，并在用户勾选时发送 `:include? true`。
- 服务端仍无条件追加一份 `:include? false` 的 `:detailed-log`，形成同 id 两份材料。
- `bundle/prepare` 的 `included-materials` 只按请求材料取内容；请求材料没有 `:content`，追加的那份又被 `:include? false` 排除。结果是用户勾选后预览仍没有日志正文，且 `material-view` 出现重复条目。

### 修改

新增按 `:id` 合并的 helper；服务端保留标题/类型/最新正文，请求只覆盖 `:include?` 与非空 `:purpose`：

```clojure
(defn- merge-log-material
  [materials log]
  (let [requested (some #(when (= (:id log) (:id %)) %) materials)
        purpose (not-empty (str (:purpose requested)))]
    (conj (vec (remove #(= (:id log) (:id %)) materials))
          (cond-> log
            requested (assoc :include? (true? (:include? requested)))
            purpose (assoc :purpose purpose)))))
```

`prepare-bundle` 改为：

```clojure
materials (if-let [log (and (= :ok (:status snap)) (log-material snap))]
            (merge-log-material (vec (:extra-materials request)) log)
            (vec (:extra-materials request)))
```

不要改 `log-material` 的 `:content` 来源；不要信任客户端传入的 `:content`。`bundle/prepare` 继续以服务端合并后的材料生成 `:sections` 与 `:extra-materials`。

### 测试

`test/hc/server/diagnostics/bundle_test.clj` / `smoke_test.clj` / `persistence_test.clj` 增加：

1. 请求含 `{:id :detailed-log :include? true :purpose "..."}` 时，合并后只有一份 `:detailed-log`，`included?` 为 true，预览 `:sections` 出现日志正文。
2. 请求 `:include? false` 或缺省时，日志材料仍列出但 `included?` 为 false，正文不进入预览。
3. 请求提供空 purpose 时，保留服务端默认 purpose，且不因空 purpose 拒绝。
4. 请求携带伪造 `:content` 时，导出内容仍来自服务端 `log-material`，不采用客户端内容。
5. 客户端 `bundle-materials` 的完整字段合并测试已在 `test/hc/client/diagnostics/model_test.clj`；服务端合并后应断言同 id 不重复。

### 验收

- 用户勾选“Detailed request log”后，诊断预览包含脱敏日志正文，且材料列表只有一份。
- 未勾选时日志正文不进入预览；客户端无法通过伪造 `:content` 注入内容。

---

## 3. 消息翻译“替换当前选区”接口

**归属：client-conversation（主）、client-workbench（草稿范围替换 mutation）**

### 文件与现状

`src/hc/client/conversation/view.cljs:869-877`：

```clojure
(react/createElement
 speech-ui/translation-controls
 #js {:messageId (:id message)
      :versionId (:current-version message)
      :text (:copy-text message)
      :onInsert (fn [text]
                  ((:insert-transcript! callbacks)
                   {:session-id session-id
                    :text text}))})
```

现状：

- `translation-controls` 只收到消息正文与 `onInsert`，没有草稿选区、没有替换回调。
- `message-item`（`conversation/view.cljs:799-...`）不接收 composer/draft 选区；选区只在 `conversation-surface` 的 `view-model` / `props` 中。
- 因此消息派生翻译面板只能复制/插入，无法安全替换当前草稿选区；本轮未添加假按钮。

### 需要的数据形状

`conversation-surface` 调用 `message-item` 时（当前 `conversation/view.cljs:1779-1790` 附近）传入草稿选区快照与替换回调：

```clojure
{:draft-selection {:start int
                   :end int
                   :text string
                   :draft-version string-or-nil}
 :draft-text string
 :on-replace (fn [{:keys [start end expected-text text]}]
               ((:replace-transcript! callbacks)
                {:session-id session-id
                 :start start
                 :end end
                 :expected-text expected-text
                 :text text}))}
```

选区来源：`view-model` 的 `:composer`，优先 `:selection`，否则用 `:cursor` 作为零宽选区：

```clojure
(let [composer (:composer view-model)
      value (or (:value composer) "")
      selection (:selection composer)
      start (or (:start selection) (:cursor composer) 0)
      end (or (:end selection) start)
      start (max 0 (min (count value) start))
      end (max start (min (count value) end))]
  {:start start
   :end end
   :text (subs value start end)
   :draft-version (:version (:draft props))})
```

`message-item` 把 `:draft-selection` 与 `:on-replace` 原样传给 `translation-controls`：

```clojure
#js {:messageId (:id message)
     :versionId (:current-version message)
     :text (:copy-text message)
     :selection (clj->js draft-selection)
     :onReplace (fn [payload]
              ;; speech 侧以 CLJS map 调用；若跨 JS 边界则归一化。
              (on-replace (if (map? payload)
                            payload
                            (js->clj payload :keywordize-keys true))))
     :onInsert ...}
```

### 调用契约

`translation-controls` / `translation-panel` 需要在发起翻译时捕获：

- `source`：当前草稿全文；
- `selection`：`{:start :end :text}`；
- `request-text`：实际发送给翻译服务的片段（有选区时为片段，否则为全文）。

结果块只有在 `translation-model/replace-plan` 返回 `:replace` 时显示“替换当前选区”：

```clojure
(let [plan (translation-model/replace-plan
            {:source current-source
             :selection current-selection
             :result result})]
  (when (= :replace (:action plan))
    (button "替换当前选区"
            #(on-replace {:start (:start current-selection)
                          :end (:end current-selection)
                          :expected-text (:text current-selection)
                          :text (:translated-text result)}))))
```

`translation-model/replace-plan` 已实现并测试：源文、选区起止、选中文字任一变化即返回 `:blocked`；面板此时只保留复制/插入。

### workbench 侧 mutation

`client-workbench` 需新增 `:replace-transcript!`（或等价草稿范围替换 mutation）：

```clojure
:replace-transcript! (fn [{:keys [session-id start end expected-text text]}]
                       (let [d (draft/draft (:ui/draft db) session-id)
                             old (or (:text d) "")
                             start (max 0 (min (count old) (long (or start 0))))
                             end (max start (min (count old) (long (or end start))))]
                         (when (= expected-text (subs old start end))
                           ;; 用 old 的前缀 + text + 后缀生成 next-text，
                           ;; cursor 设为 start + count(text)，再走 edit-draft!
                           )))
```

要求：

- 不匹配 `expected-text`、越界、session 为空时拒绝，不修改草稿；
- 不覆盖录音/翻译期间用户新写入的文字；
- 失败给用户“选区已变化，请复制或插入”的明确提示，不静默替换。

### 测试

1. `test/hc/client/speech/translation_model_test.clj` 已有 replace/blocked 纯逻辑；补“选区移动/源文修改/整文翻译无替换目标”。
2. 新增 `test/hc/client/conversation/selection_dom_test.cljs` 或扩展 `selection_browser_test.cljs`：
   - 草稿有选区时，消息翻译面板出现“替换当前选区”；
   - 点击后 `onReplace` 收到 `{:start :end :expected-text :text}`；
   - 选区变化后按钮消失或点击被拒绝，草稿不变。
3. workbench 纯测试：`expected-text` 不匹配、越界、空 session 均拒绝；匹配时只替换指定范围并更新 cursor。
4. 真实 React 渲染：`translation-controls` 在 `:selection` 非空且结果存在时渲染替换按钮；无选区时只渲染复制/插入。

### 验收

- 消息翻译面板可对“请求时选区”执行替换；
- 原文、选区或草稿在请求后变化时，替换被阻止，用户仍可复制/插入；
- 历史消息正文永不被原地覆盖。
