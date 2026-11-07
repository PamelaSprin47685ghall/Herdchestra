# `hc.client.appearance` — 外观与可访问性偏好

本目录只含纯模型与纯视图；浏览器应用层在上一级的 `appearance.cljs`。

## 1 文件职责

| 文件 | 职责 |
|---|---|
| `model.cljc` | 偏好值的归一化、钳制、Fulcro 键映射与动作 reducer。不触 DOM、localStorage 或 Fulcro。 |
| `view.cljc` | `settings-tree`：与 `hc.client.settings.view` 同形的 hiccup 节点树；原生按钮、range、checkbox，键盘行为交给平台。 |
| `../appearance.cljs` | `apply!`：把归一化状态写到 `document.documentElement` 的 `data-*` 与 CSS 变量，并确保一段高对比度/减少动态的样式；另转发 `from-db`/`to-db`/`to-ui`/`settings-tree`。 |

## 2 数据契约

状态是四个可序列化字段：

```clojure
{:theme :system | :light | :dark      ; 未知值回退 :system
 :font-scale 80..200                  ; 步长 10；字符串数字可解析
 :contrast :normal | :high            ; 未知值回退 :normal
 :reduced-motion :system | :reduce | :no-preference}
```

- `normalize` 接受部分 map 或非 map，始终返回完整状态；未知值回退默认，不抛错。
- 旧布尔值仍可读取：`true` 视为 `:reduce`，`false` 视为旧的系统默认。
- `from-db` 读 `:ui/theme`、`:ui/font-scale`、`:ui/contrast`、`:ui/reduced-motion`。
- `to-db` 返回上述四个 Fulcro 键；`to-ui` 返回 `set-appearance` mutation 使用的扁平键。
- `reduce-action` 返回 `{:state next :effects [{:effect :apply} {:effect :persist}]}`；effect 只是值，由宿主决定如何应用与持久化。无变化的动作不产生 effect。
- `theme-attribute` 把 `:system` 映射为样式表使用的 `auto`；`reset-font` 只把字号恢复 100%。

## 3 边界

- 本目录不写 localStorage、不派发 Fulcro mutation、不改服务器状态或会话内容。
- `:theme :system` 只表示“跟随系统”这一偏好值；实际媒体查询与样式解析由宿主/浏览器负责。
- `apply!` 为 `:reduced-motion :system` 写 `data-reduced-motion="auto"`，并在样式块内用 `prefers-reduced-motion` 规则处理；`:reduce` 写 `true`，`:no-preference` 写 `false`。
- 持久化由 `hc.client.workbench.storage` 负责（只选 `:theme`、`:font-scale`、`:contrast`、`:reduced-motion` 四个键）。
- `apply!` 可重复调用；没有 `js/document` 时是 no-op。

## 4 测试

`test/hc/client/appearance_test.clj` 覆盖：

- 默认值完整、字号钳制与 10% 对齐、未知主题/对比度回退；
- `reduce-action` 的状态迁移、重置与 `:apply`/`:persist` effect；
- `:system` 主题的 `auto` 属性、减少动态三态与仅字号重置；
- `settings-tree` 的节点形状与可访问属性。

## 5 非目标

- 不做主题编辑器之外的外观能力（背景图、字体族、逐会话外观）。
- 不在本目录持久化，也不直接改服务器配置。
- 不替代 `hc.client.settings.view` 的设置框架；本目录只提供外观分区树。
