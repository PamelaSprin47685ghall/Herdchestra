# `hc.core.ufss` — EDN + UFSS 读取器与 Pretty Printer

本包实现根 `AGENTS.md` §6.3 的 **Universal Fence-Scaled String（UFSS）**。它只依赖 Clojure 标准库，不依赖任何其他 `hc.*` 包。

## 职责与边界

- 读取：标准 EDN、标准 Clojure 转义字符串、`#^n"..."#^n` 单行原始围栏、`#^n"""..."""#^n` 多行原始块（含五步公共缩进剥离）。
- 打印：把任意受支持的 EDN 值确定性渲染为面向大模型的 EDN + UFSS 文本；字符串严格按 §6.3 的阶梯选择普通字符串、单行围栏或缩进块。
- 归一化模型提交：`read-input` 在 EDN + UFSS 之外接受 JSON 的 `null`、`"key": value`（含常见空格冒号）与 `\/` 转义。
- 不负责：传输层 JSON 信封、工具程序 `(defprogram ...)` 的求值、Scope/Pair Hint 投影。这些属于上层组合包。

## 命名空间

| 命名空间 | 责任 |
|---|---|
| `hc.core.ufss` | 公共门面：`read-str`、`read-input`、`read-all-str`、`read-str-safe`、`write-str` |
| `hc.core.ufss.reader` | 游标、错误定位、EDN + UFSS 读取 |
| `hc.core.ufss.printer` | 字符串阶梯、标量与集合渲染 |

上层统一使用 `(require '[hc.core.ufss :as ufss])`；根规格中的 `ufss/read-str`、`ufss/write-str` 即此门面。

## 公共 API

```clojure
(ufss/read-str s)            ; 严格读取一个 EDN+UFSS 值；尾部非空白报错
(ufss/read-str s opts)       ; opts 为内部读取选项；read-input 等价于 {:json true}
(ufss/read-input s)          ; 模型提交归一化：EDN+UFSS 加 JSON 便利
(ufss/read-all-str s)        ; 读取全部 form，返回向量
(ufss/read-str-safe s)       ; {:ok value} 或 {:error ex-data}
(ufss/write-str v)           ; 渲染任意受支持 EDN 值
```

`write-str` 的字符串实参按“字符串字面量”渲染，因此对任意 Unicode 字符串 `s`：

```clojure
(= s (ufss/read-str (ufss/write-str s)))
```

## 读取语义

### 四种字符串形态

1. `"..."`：标准 EDN/Clojure 转义字符串。支持 `\t \r \n \\ \" \b \f \uXXXX` 与 Clojure 的八进制 `\NNN`（0–377）；`read-input` 另接受 `\/`。字符串内允许原始换行与控制字符（宽松输入）。
2. `#^n"..."#^n`（`n >= 1`）：单行原始围栏。反斜杠、引号、`#` 全部按字面量保留；读取器寻找第一个 `"` 后跟 `n` 个 `#` 的前缀。若输入中出现更长的 `#` 串，多出的 `#` 留给后续输入，通常表现为尾随数据错误；打印器取 `n = 1 + 正文中引号后最长 # 串`，因此生成文本的结束边界无歧义。读取器对围栏内的换行按字面量接受（宽松输入）；打印器不会为多行字符串生成单行围栏。
3. `#^n"""\n...\n<indent>"""#^n`（`n >= 0`）：多行原始块。开始围栏后必须紧跟一个 `\n`；结束围栏必须位于独立一行，左侧只允许空格 `<indent>`。结束判定同样是 `"""` 后跟 `n` 个 `#` 的前缀；打印器取 `n = 1 + 正文中 """ 后最长 # 串`（`n = 0` 仅当正文不含 `"""` 且不含 `\`），因此生成文本不会把正文行误判为结束行。正文按 `\n` 切分后，每个非空行必须以前缀 `<indent>` 开头，剥离后重新用 `\n` 连接；空行原样保留。非空行前导空格不足时报 `:UFSS_INDENT_VIOLATION`。
4. 控制字符没有单独形态：打印器回退到标准转义字符串。

`n = 0` 的块写作 `"""..."""`，不带 `#`。结束围栏在 `n = 0` 时其后不得紧跟 `"`，以免把四个连续引号误判为结束。

### EDN

支持 `nil`、布尔、整数（`Long` / `BigInt` / `N` 后缀）、浮点、`M` 后缀 `BigDecimal`、比例 `1/2`、字符、字符串、关键字、符号、列表、向量、映射、集合、注释 `;`、`#!` 行注释、`#_` 丢弃、`##Inf`/`##-Inf`/`##NaN`、标签字面量。字符字面量的八进制 `\oNNN` 与字符串的 `\NNN` 一样限制在 0–377；字符字面量超出报 `:UFSS_INVALID_CHAR`，字符串转义超出报 `:UFSS_INVALID_ESCAPE`。`#!` 与 `;` 在空白可出现的位置等价，包括 `read-str` 读取完唯一值之后的尾部。

- `#uuid "..."` 读取为 `java.util.UUID`。
- `#inst "..."` 读取为 `java.util.Date`，使用 `java.time.Instant/parse`（proleptic Gregorian）。这是对 Clojure 旧 `clojure.instant` 的刻意替换：旧实现在 1582 年格里高利历切换前会偏移十天，破坏双射。打印 `java.time.Instant` 时仍用 `#inst`，但读取返回 `Date`，亚毫秒精度会截断；若需要类型严格双射，请使用 `java.util.Date`。`java.util.Date` 的可表示范围窄于 `java.time.Instant`；超出该范围的 `Instant` 无法被读回，打印器直接抛 `:ufss/write-error`，不产出不可读文本。
- 未知标签读取为 `clojure.lang.TaggedLiteral`。打印器拒绝用 `tagged-literal` 承载保留标签 `uuid`/`inst`；请直接打印 `UUID`/`Date` 值，避免读取时类型被解释为 UUID/Date。
- 标签 token 必须是合法符号：`#1`、`#nil`、`#@foo` 等报 `:UFSS_INVALID_TAG`，不会产生打印器无法表示的 `TaggedLiteral`。名字空间标签如 `#foo/bar` 合法。
- 比例（`1/2`）不接受 `N`/`M` 后缀；`1/2N`、`1/2M` 报 `:UFSS_INVALID_NUMBER`，绝不静默丢弃后缀。
- 映射与集合拒绝重复键/元素（`:UFSS_DUPLICATE_KEY`、`:UFSS_DUPLICATE_SET_ELEMENT`）。
- 命名空间映射 `#:ns{...}`、reader conditional `#?`、quote/var/eval 与 `@ ^ \` ~` 读取宏一律报 `:UFSS_UNSUPPORTED_SYNTAX`；UFSS 的 `#` 只用于围栏、集合、丢弃、行注释、特殊浮点与标签。

### `read-input` 的 JSON 便利

- `null` → `nil`；`read-str` 仍把 `null` 读作符号。
- 字符串键后的 `:` 作为 JSON 键值分隔符消费：紧邻冒号总是分隔符；空格冒号在冒号后为空白（含逗号）、`{`、`[`、`"`、`}`、`]` 或输入结束时也是分隔符。`{"a" :b}`、`{"a" :1}` 这类冒号后直接跟关键字 token 的写法仍按 EDN 关键字值读取。`{"a":1}` 在 `read-input` 中是 `{"a" 1}`，在 `read-str` 中仍是合法 EDN `{"a" :1}`。
- `\/` → `/`。

## 打印阶梯（§6.3）

设当前字段缩进为 `indent`：

1. 含控制字符（ASCII < 32 且非 `\n`/`\t`，另含 DEL 与 C1，以及未配对代理项）→ 标准转义 `"..."`。
2. 不含 `\n`：
   - 不含 `"`、`\`、制表符或其他控制字符 → `"` + `s` + `"`；
   - 否则取最小 `n >= 1` 使 `"` + `n` 个 `#` 不在 `s` 中出现 → `#^n"` + `s` + `"#^n`。
3. 含 `\n`：
   - 不含 `"""`、不含 `\` → `n = 0`；
   - 否则取最小 `n >= 1` 使 `"""` + `n` 个 `#` 不在 `s` 中出现；
   - 输出 `#^n"""\n` + 每个非空行前置 `indent` + `\n` + `indent` + `"""#^n`。

两处 `n` 都按“正文中对应前缀后的最长 `#` 串 + 1”计算，与“最小 `n` 使结束串不出现”等价。

### 规格未明说的三个补全

- **单行引号歧义**：`s` 恰为 `"` 或以 `""` 开头时，`#^n"` + `s` + `"#^n` 在开围栏后会出现连续三个 `"`，被词法器当作多行开头。此类字符串改走块形态（`n` 按第 3 步选择），仍然零转义且双射。
- **DEL/C1 与未配对代理项**：§6.3 只点名 ASCII < 32。本包把 DEL（127）、C1（128–159）与未配对 UTF-16 代理项也送入转义回退，避免终端出现不可见控制字符或非法 UTF-8。配对代理项保持原样。
- **制表符与普通字符串**：根 §6.3 第 2 步只列 `"` 与 `\`；本包按第 1 步的控制字符口径把制表符也排除出普通字符串。含 `\t` 但不含换行的字符串走单行围栏；含 `\t` 且含换行的字符串走块形态，`n` 按第 3 步。
- **数值类型边界**：浮点类型只接受 `Double`；`Float` 无法在不改变数值身份的前提下经 `read-str` 读回，打印器抛 `:ufss/write-error`。`BigInteger` 打印为 `<digits>N`，读取器返回 `BigInt`：数值 `=` 相同，类型身份不同。

### 集合排版

- 映射：若任一键值对的渲染含换行，则每个条目一行，缩进为当前 `indent` 加两个空格，右花括号独占一行；否则单行 `{:a 1 :b 2}`。
- 向量、列表、集合：任一元素含换行则每元素一行，否则单行。
- 多行字符串的内容行与结束围栏使用该值的当前 `indent`，因此嵌套在映射/向量里仍能被读取器的缩进剥离精确还原。

## 出站呈现与提交解析契约

- **模型可见的结构化文本一律用 `write-str`**：工具回执、看板快照、搜索上下文、报告载荷、错误详情、park 诊断、Pair Hint 载荷。`pr-str` 只用于内部日志/排序/指纹，不是呈现格式；它会把引号、反斜杠与换行变成 `\"`、`\\`、`\n` 噪音。
- **模型提交的字符串参数一律用 `read-input`**：JSON、标准 Clojure 转义字符串与 UFSS 都接受。`clojure.edn/read-string` 不能读取 `#"..."#`（它按正则字面量处理），不能作为提交解析器。
- **错误详情保留原始 EDN 值**：例如 `:found` 应存表单/值本身，由 `write-str` 呈现；预先 `pr-str` 成字符串会把转义噪音带进回执。
- **工具回执必须真正进入对话**：`write-str` 只负责渲染；组合层还需把渲染结果作为 `:role :tool` 消息投影到下一次模型请求。
- 传输层 provider JSON 信封（tool call 的 JSON 参数、MCP/HTTP 信封）不受此契约约束。

`test/hc/core/ufss_presentation_test.clj` 固定了回执、搜索上下文、看板快照、`pr-str` 对照与 JSON/EDN/UFSS 工具参数归一化的呈现契约。

## 模型工具回执接线合同

UFSS 只负责文本；工具回执能否真正到达模型还取决于 Owner 会话投影。0.0.1 的接线要求：

1. **assistant tool-call 必须保留 `:arguments`**：`handlers` 写入 `:role :tool-call` 时要带模型提交的参数；`owner/state` 的 `message->scope-item` / `scope-item->message` 必须把它带进 `{:role :assistant :tool-calls [{:id ... :name ... :arguments ...}]}`。丢参数会让模型在下一轮看到自己“调用过”但不知道传了什么。
2. **tool result 必须带 `:tool-call-id`、`:name`、`:error?`**：`project-tool-result-guard` 写入 `:role :tool`，Scope 投影必须保留 `:error?`，模型适配器才能把失败标记为 `ToolExecutionResultMessage.isError`，而不是只把失败藏在正文里。
3. **结构化工具结果不能因 `:text` 字段绕过 UFSS**：结果是 map 时用 `program/render-tool-result`（或 `write-str`）渲染整个结构；只有结果本身是字符串时才直接使用。`{:text "..."}` 不能替代完整回执。
4. **`conversation-tail` 必须有界且角色安全**：Scope 投影后取最近 32 条模型消息；投影失败时用 `hc.core.scope.runtime` 的纯适配器重建合法 `:user/:assistant/:tool` 消息，不能把 `:role :tool-call` 原样交给模型适配器。

`test/hc/core/ufss_presentation_test.clj` 的 `tool-call-and-result-message-presentation` 固定了 `:arguments`、`:error?` 与嵌套 UFSS 回执的往返形状。

## 错误模型

所有预期读取失败抛出 `clojure.lang.ExceptionInfo`，`ex-data` 形如：

```clojure
{:type :ufss/read-error
 :error :UFSS_INDENT_VIOLATION
 :offset 42
 :line 3
 :column 1
 :expected "a non-empty line indented by at least 4 spaces, or an empty line"
 :found "  b"}
```

错误码：`:UFSS_UNEXPECTED_EOF`、`:UFSS_UNMATCHED_DELIMITER`、`:UFSS_UNTERMINATED_STRING`、`:UFSS_INVALID_ESCAPE`、`:UFSS_UNTERMINATED_FENCE`、`:UFSS_INVALID_FENCE`、`:UFSS_MULTILINE_NEEDS_NEWLINE`、`:UFSS_INDENT_VIOLATION`、`:UFSS_INVALID_NUMBER`、`:UFSS_INVALID_TOKEN`、`:UFSS_INVALID_KEYWORD`、`:UFSS_INVALID_CHAR`、`:UFSS_ODD_MAP`、`:UFSS_DUPLICATE_KEY`、`:UFSS_DUPLICATE_SET_ELEMENT`、`:UFSS_TRAILING_DATA`、`:UFSS_UNSUPPORTED_SYNTAX`、`:UFSS_INVALID_TAG`、`:UFSS_INVALID_TAGGED_VALUE`。

`:offset` 是 UTF-16 码元下标（0 起）；`:line` 与 `:column` 是 1 起的人类可读位置，`:column` 按 Unicode 码点计数。`#` 后接非围栏、非特殊浮点的形式（如 `##foo`、`###Inf`）报 `:UFSS_INVALID_FENCE`。

`read-str-safe` 只捕获 `:type :ufss/read-error` 的 `ExceptionInfo`；参数类型错误与内部缺陷继续抛出。

打印器对无法在不改变身份的前提下表示的符号/关键字抛出 `:type :ufss/write-error`，例如符号 `1abc`、`nil`、含空白的名字，或名称含 `/`、名称以 `:` 开头却无命名空间的关键字。

## 测试

属性测试覆盖 §8.2 的 UFSS 双射不变量：

- 500 组随机 UTF-16 字符串：`(gen/fmap char (gen/choose 0 0xFFFF))` 生成任意码元，含 CJK、孤立/成对代理项；另叠加围栏压力字母表（`"`、`\`、`#`、`"""`、换行、制表符、控制字符、DEL/C1、U+2028）与显式星平面/代理项样本；
- 300 组 ASCII 字符串；
- 300 组随机嵌套 EDN 值（标量、集合、映射、标签字面量、UUID、日期、比例、BigDecimal；字符与字符串使用全 UTF-16 生成器）。

确定性用例覆盖四种字符串形态、围栏最小 `n`（单行 `n = 1..5`、块 `n = 0..4`）、缩进剥离、尾随换行、错误位置（含精确 offset/line/column）、JSON 归一化、集合排版、块字符串作为 map 键/嵌套元素、工具调用/回执形状、比例后缀、字符八进制上界、非法标签与 `:UFSS_INVALID_FENCE` 边界。

在仓库根目录运行（使用根 `deps.edn` 的 `:test` 别名）：

```bash
clojure -M:test -n hc.core.ufss-test -n hc.core.ufss-presentation-test
```

没有根 `deps.edn` 时可独立运行：

```bash
clojure -Sdeps '{:paths ["src" "test"]
                 :deps {org.clojure/test.check {:mvn/version "1.1.3"}}}' \
  -M -e "(require 'hc.core.ufss-test 'hc.core.ufss-presentation-test)
         (let [r (clojure.test/run-tests 'hc.core.ufss-test
                                         'hc.core.ufss-presentation-test)]
           (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
```

## 不变量

1. 对任意 Unicode 字符串 `s`（含孤立代理项）：`(= s (read-str (write-str s)))`。
2. 对任意受支持 EDN 值 `v`：`(= v (read-str (write-str v)))`；例外是 `NaN`（Clojure `=` 非自反）与 `java.time.Instant`（读取归一为 `Date`，亚毫秒截断）。`Float` 不在受支持值内（打印器抛 `:ufss/write-error`）；`BigInteger` 打印为 `N`、读回 `BigInt`，仅保证数值 `=`。
3. `write-str` 对系统数据只产出 EDN + UFSS，不产出 JSON 转义噪音。
4. 读取失败不返回半解析值；`read-str` 在尾部非空白时拒绝，`read-all-str` 才接受多 form。
5. 多行块的空行不参与缩进校验；非空行缩进不足是硬错误，绝不静默吞字。
