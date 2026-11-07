# hc.client.copy — 用户可见失败文案单一事实来源

> 权威顺序：根 `AGENTS.md` §7.4 旅程 8（设置与诊断） / §7.7 > `src/AGENTS.md` >
> `src/hc/client/AGENTS.md` > 本文件。

## 1 责任

`hc.client.copy.failures/failure-copy` 把错误码和少量上下文转换成统一的
`{:title :body :primary-action {:label :command} :secondary-action :severity}`。
它是 24 类失败文案的唯一事实来源；view 只渲染结果，不再各自拼字符串。

24 类文案按根 `AGENTS.md` §7.4 第 8 段重组为十类「未交付理由」分类：
`failure-reason-categories` 给出十类的编号、名称、类别说明与成员 kind，
`failure-category`/`failure-reason` 按错误码（含别名与字符串形式）查类别，
`failure-copy` 结果附加 `:reason-category`。未知错误码回退
`:indeterminate`（「无法确定具体原因」），不借用其他类别的根因；
`:transport-disconnected` 归入第四类但不计入 24 类。分类是呈现层分组，
不改变错误码与协议。

覆盖 24 类：未选模型、供应商停用、模型移除、凭据无效、授权过期、额度不足、
限流、网络不可达、响应未到、输入超容量、附件不支持、部分不可读、空间不足、
资料不可读、外部文件移动、权限撤销、工具失败、回执丢失、错过时间、备份密码错、
导入格式不支持、恢复冲突、版本过新、导出取消。`transport-disconnected` 是
独立于 `network-unreachable` 的传输分支，不计入 24 类。

## 2 契约

1. **三口径分开**：认证失败（`:invalid-credentials`/`:auth-invalid`）、
   无权限（`:permission-revoked`/`:permission-denied`）、找不到模型
   （`:model-removed`/`:model-missing`）不得共用 title/body；额度不足
   （`:quota-exhausted`）也不得与限流（`:rate-limited`）混用。
2. **动作是命令关键字**：主/次操作给可执行 keyword，例如
   `:settings/select-model`、`:command/stop-run`、`:export/restart`，
   不允许含糊的 `:retry`。
3. **保存措辞有门槛**：`已保留`/`已保存` 只在 `:saved? true` 时出现。
   `:rate-limited` 只有 saved 时才写“已保留本轮内容”；false 时写
   “本轮内容仍在输入框，尚未确认本地保存”。`export-cancelled` 无论 saved
   与否都不得出现这两个词。
4. **未知用量不写 0**：`:unknown-usage? true` 追加“用量未知，不按零计算”，
   任何分支都不把未知写成数字 0。
5. **未知错误码不借因**：未识别的 code 返回“无法确定具体原因”和诊断入口，
   不套用其他类别的根因。

## 3 失败模式

- view 直接拼英文/服务端 message，导致认证/权限/模型三类混淆；
- 在未确认本地保存时写“草稿已保留”；
- 把未知用量显示为 0；
- 主操作只有“重试”，没有具体入口；
- 新增错误码没有对应分支，落到通用文案却不带诊断入口。

## 4 复检

```bash
clojure -X:test:client :nses '[hc.client.copy.failures-test]'
```

测试必须覆盖 24 类完整性、三口径互异、`:saved?` 两分支、未知用量不含 0、
`:export-cancelled` 不出现已保存/已保留、network 与 transport 分离。
