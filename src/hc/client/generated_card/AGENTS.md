# `hc.client.generated-card` — 生成的交互表单/图表卡

> 权威：根 `AGENTS.md` §6.5 UX-20（交互回复、表单、图表、数据表、安全提交）> `src/hc/client/AGENTS.md` > 本文件。
> 本包只负责渲染与本地交互；不构造命令、不发模型请求、不写领域终态。

## 1 边界

拥有：

1. 模型生成的表单卡：字段标签、单位、默认值、必填、条件显示、校验、预览、本地保存与“发送给助手”动作。
2. 模型生成的图表卡：柱状/折线 SVG、坐标轴、单位、时间范围、来源/生成方式、缺失值、精确值读数、图例、数据表、筛选、排序、分页、导出。
3. 可读文字回退与复制；全屏本地状态；响应式/可访问样式。
4. 纯模型 `hc.client.generated-card.model`（CLJC）与 React 面 `hc.client.generated-card.view`（CLJS）。

不拥有：

- 会话消息、服务端卡片数据、命令去重、模型/工具执行；
- 任何网络或模型调用；
- 会话外壳、权限、供应商或账号界面。

**关键不变量**：本包没有任何 `fetch`/XHR/Sente/模型端口调用。编辑、筛选、排序、分页、图表读数、本地保存、重置、全屏都只改本地 React 状态。唯一宿主动作是显式点击“发送给助手”后调用注入的 `:on-submit!`；宿主是否真的发模型请求由宿主决定，本包不代发。

## 2 命名空间

| 命名空间 | 责任 | 纯 CLJC |
|---|---|---|
| `hc.client.generated-card` | 公共纯门面 | 是 |
| `hc.client.generated-card.model` | 卡片归一化、字段/条件/校验、提交载荷、图表/表格/筛选/排序/分页/CSV/几何输入 | 是 |
| `hc.client.generated-card.view` | React/Framework7 兼容视图、SVG、数据表、导出、全屏 | 否 |

## 3 卡片数据合同

卡片是普通 EDN map；`model/normalize-card` 补默认值并保留未知展示字段。

```clojure
{:card-id "gen-1"
 :kind :form                       ; 或 :chart；缺省时按 :form/:chart 推断
 :title "提交月度预算"
 :purpose "填写本月预算并选择是否附带说明。"
 :state :ready                     ; :ready | :generating | :failed
 :generated? true
 :generated-by "assistant"
 :generated-at "2026-11-03T10:00:00Z"
 :provenance :model                ; :model | :user | :assumption | :mixed
 :source "meter.csv"               ; 可选
 :time-range "2026-01..2026-05"    ; 可选；图表顶部或图表内也可放
 :illustrative? false
 :text-fallback "..."              ; 可选
 :show-table? false                ; 图表初始是否展开数据表
 :form {...} | :chart {...}}
```

### 表单

```clojure
{:form
 {:submit-label "发送给助手"
  :local-label "仅保存到本卡"
  :preview-label "预览将发送内容"
  :fields
  [{:name :amount :type :number :label "预算金额" :unit "元"
    :required? true :default 100 :min 0 :max 10000 :step 1}
   {:name :date :type :date :label "生效日期" :required? true
    :default "2026-11-03" :timezone "Asia/Shanghai"}
   {:name :mode :type :select :label "预算模式" :default "monthly"
    :options [{:value "monthly" :label "按月"} {:value "yearly" :label "按年"}]}
   {:name :note :type :textarea :label "说明" :max-length 120}
   {:name :has-attachment :type :boolean :label "有附件"}
   {:name :attachment :type :text :label "附件说明"
    :visible-when {:field :has-attachment :equals true}}
   {:name :token :type :secret :label "凭据" :required? true}]}}
```

字段类型：`:text`、`:textarea`、`:number`、`:range`、`:date`、`:select`、`:boolean`、`:secret`。

- 条件字段：`:visible-when` 支持 `{:field f :equals v}`、`{:field f :in [...]}`、`{:field f :truthy? bool}`。
- 隐藏字段保留本地草稿但不渲染、不校验、不提交；预览列出“隐藏字段不会提交”。
- 秘密字段（`:secret? true` 或 `:type :secret`）只保留在本卡，**永不进入提交载荷**；必填也不阻塞发送。
- 校验：必填、数字/范围、`YYYY-MM-DD`、select 选项、最大长度；错误显示在字段旁并保留其他输入。select 的默认值和 DOM 改选值统一回选项原值，默认值不再以字符串与 keyword 选项比较。
- 提交载荷只含可见、非秘密字段；数字/范围转为数字，布尔保持布尔。`model/submission` 返回 `:ok?`、`:values`、`:fields`、`:excluded-secret-fields`、`:hidden-fields`、`:text`。

### 图表

```clojure
{:chart
 {:type :bar                       ; :bar | :line
  :x-label "月份" :y-label "数值" :unit "kWh"
  :time-range "2026-01..2026-05"
  :page-size 8
  :series [{:key :usage :label "用量" :unit "kWh"}
           {:key :cost :label "费用" :unit "元"}]
  :data [{:x "2026-01" :usage 120 :cost 30}
         {:x "2026-02" :usage nil :cost 32}
         {:x "2026-03" :usage 150 :cost nil}]}}
```

- `nil`/空字符串/非有限数视为缺失；图表跳过，数据表显示“缺失”，汇总计数；**绝不按零绘制**。
- 每张数值图表有“查看数据”入口；表格列名含单位，支持类型感知排序（缺失值始终在末尾）、分页、当前页/全部 CSV。
- 筛选（类别、隐藏全缺失行）同时作用于图表、汇总和表格；顶部保留筛选 chip 与清除/重置；空筛选显示“没有可绘制数值”，不保留旧图形。
- 图表标题、横纵轴、单位、时间范围、来源、生成方式（模型/用户/假设）、缺失值、图例均可见；`illustrative?` 显示“示意”与“不是真实测量”。多系列单位不同时纵轴标“多单位”，不冒充单一单位。
- 颜色不是唯一编码：数据点有可聚焦的 `aria-label`、精确值读数、图例文字和数据表。
- 导出：SVG（含标题、轴、单位、来源、筛选、时间范围、示意标记）和 CSV（当前页/全部筛选，含标题/来源/筛选/范围元数据）。导出使用浏览器 `Blob`/`URL`，延迟释放对象 URL；SSR 下按钮可见但点击无副作用。

## 4 挂载合同（conversation-ui）

会话消息由 `hc.client.conversation.view/message-item` 渲染。推荐把卡片作为助手消息的 `:generated-card` 字段挂载：

```clojure
;; conversation/view.cljs
(:require ["react" :as react]
          [hc.client.generated-card.view :as generated-card])

;; message-item 内，正文/附件之后：
(when-let [card (:generated-card message)]
  (react/createElement generated-card/generated-card
                       #js {:key (str "generated-" (or (:card-id card) (:id message)))
                            :props {:card card
                                    :on-submit! (:submit-generated-card! callbacks)}}))
```

或使用辅助函数：

```clojure
(generated-card/generated-card-element
 {:card card
  :on-submit! (:submit-generated-card! callbacks)})
```

规则：

1. **必须作为 React 元素挂载**，不要 `(generated-card/generated-card props)` 直接调用；该组件使用 hooks，直接调用会把 hooks 合入调用者。`generated-card-element` 是安全包装。
2. 用 `:key`（card-id 或 message-id）保证新卡片替换旧卡片时本地表单/筛选状态重置。
3. `:on-submit!` 是可选宿主回调；缺失时“发送给助手”禁用并显示“当前卡片只支持本地填写”。宿主回调若真的发模型请求，应在自己的命令面标注“发送给助手”，并自行去重。
4. `:on-local-save!` 可选，仅本地保存；不触发模型。回调载荷排除 secret 字段，并列出 `:excluded-secret-fields`。
5. 本包不修改 `conversation/AGENTS.md` 的既有回调合同；如需新增 `:submit-generated-card!`，由 conversation-ui 在其文档中登记。

## 5 可访问性与响应式

- 卡片 `role="group"`，标题 `aria-labelledby`；徽标文字“生成的交互内容”。全屏 Escape 关闭；复制文字使用当前表单值/当前筛选，不用初始值。
- 字段 `<label>` 与控件关联；必填 `*` + 语义；错误 `role="alert"`；帮助文本 `aria-describedby`。
- 按钮热区 ≥44px；图表数据点可 Tab 聚焦并有可读 `aria-label`；精确值读数 `role="status"`。
- 手机单列；图表横向滚动容器；桌面表单两列；全屏为本地覆盖层，退出不重置表单/筛选。
- 主题/窗口变化不清空本地状态；`prefers-reduced-motion` 关闭动画。

## 6 测试与 smoke

```bash
# 纯模型（JVM/CLJC）：校验、条件字段、秘密/隐藏字段、筛选/排序/分页、缺失值、CSV、几何输入
clojure -M:test -n hc.client.generated-card-test

# 真实 React SSR（react-dom/server）：表单/图表/生成中/失败/示意状态
./test/js/verify_generated_card_ui.sh

# 真实 DOM 交互（jsdom + createRoot + act）：本地保存、校验、显式发送、
# 秘密/隐藏字段排除、图表筛选/重置/数据表切换、fetch 零调用
./test/js/verify_generated_card_dom.sh
```

当前 smoke：

- `hc.client.generated-card.ui-test`：4 tests / 49 assertions，真实渲染 `generated-card` 元素树，检查生成标识、字段标签/单位/必填、隐藏条件字段、秘密字段提示、本地/助手动作、图表轴/单位/来源/时间范围、数据表缺失值、导出入口、生成中/失败/示意状态。
- `hc.client.generated-card.dom-test`：2 tests / 18 assertions，jsdom 中挂载真实组件，点击本地保存、触发无效/有效发送、检查秘密/隐藏字段不进入载荷、筛选/重置/数据表切换，并断言 `fetch` 调用为 0。
- `hc.client.generated-card-test`：7 tests / 88 assertions，纯模型校验、条件字段、提交载荷、筛选/排序/分页、缺失值、CSV、几何输入。

已知限制：SVG 导出是静态图，不含交互；未实现 PNG/Canvas 导出；图表只支持柱状/折线与一个类别轴 + 数值系列；全屏是本地覆盖层，不改变会话外壳。
