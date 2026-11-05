# HISTORY：万象术历史取舍与丢失信息

> 本文件记录 `AGENTS.md` 重述过程中丢失的历史信息：旧规则、被拒方案、演进叙事、带日期裁决、外部实测证据。
> `AGENTS.md` 只写当前规格；当前规格不依赖本文件。本文件按主题组织，不按日期流水。

## 旧 AGENTS.md §9 历史内容

## 旧 §9：架构演进、设计决策台账、前期探索与参考索引

本章汇集万象术在形成独立全栈架构过程中的关键设计取舍、被否决方案的反面教训、前期探索项目（`a0lite` 与宿主插件时期）的可复用工程事实及外部权威技术规范索引。

### 9.1 架构演进脉络与合规边界

1. **与 Agent Zero（A0）的干净边界及许可证隔离**：
   * 万象术是一套从零独立设计与实现的自治多智能体工程系统（Clean-Room Implementation），与早期基于 Agent Zero（MIT License，<https://github.com/agent0ai/agent-zero>）的修改版探索属于两个独立项目。A0 仅作为理解“为何不能把所有软件塞进单一大容器、为何不能把工具调用退化为终端命令字符串、为何需要结构化会话与产物边界”的参照物。
   * **第三方依赖许可证硬纪律**：不直接复制外部参考项目的业务实现、提示词或前端资产；引入任何外部库或命令行工具前必须核查许可证传染性——例如 PyMuPDF 采用 AGPL-3.0（若通过网络服务向用户提供能力会触发完整源码披露义务），在需要宽松开源/商业分发边界的模块中，必须排除未获商业授权的 AGPL 组件或将其替换为宽松许可方案。
2. **四阶段技术栈收敛路线**：
   * **第一阶段（容器瘦身探索 `a0lite`）**：尝试将 Agent Zero 封装为 Flatpak 应用并剥离预装巨型镜像，确立了“复用宿主机已有服务（如共享宿主机 Ollama 端点与模型存储）、按需供应运行环境”的初步认识。
   * **第二阶段（多宿主插件时期）**：在 OpenCode / Cline / OMP 等宿主上验证了 `Plan` 四里程碑底稿、`(review {:findings ...})` 换任接力与上下文屏障、`Pair Hint` 双语前缀链、`Scope` 单世记忆修剪、程序化工具面（`where` / `park` / `unpark`，以及后来删除的 `fission`）等核心机制；同时暴露出**宿主寄生架构的根本瓶颈**（宿主内部私有 API 与压缩逻辑脆弱、单点 `Manager/Coder` 上下文汇总瓶颈、缺乏跨个体文件事务与写锁保护、插件重载易丢内存花名册）。
   * **第三阶段（环境分离与强类型执行层评估）**：明确“环境供应与执行分离”（Pixi 托管自身 SDK 与跨平台锁定环境 + MSYS2 `UCRT64` 供应 Windows 原生工具包），并系统比较了 Python、F#/.NET（FSI）、Scala、Kotlin 与 Clojure/JVM。
   * **第四阶段（Clojure/JVM 独立全栈与 Swarm STM 合流）**：最终确立以 **Clojure/JVM + Missionary + STM** 作为服务端控制与并发底座，将宏观中心化换任接力（`Steward` + `Plan` + `Reviewer`）与微观去中心化蜂群（`Swarm STM 看板` + `(mtime, size)` 受保护提交 + `take-or-die` 动态令牌）无缝咬合；前端统一为 **ClojureScript + Fulcro + Framework7 React**，经 **Sente + 内部 AG-UI** 同步当前态，模型层通过 **LangChain4j 低层 `ChatModel`/`StreamingChatModel`** 对接各供应商，结构化字面量全面采用 **EDN + UFSS**。
3. **核心概念渊源对照（从 Vibe Mode / 宿主插件到独立版万象术）**：

| 维度 | 早期原型（Vibe Mode / 宿主插件） | 万象术独立版终局形态 | 演进原因 |
|---|---|---|---|
| 运行底座 | 寄生于 OpenCode / Cline / OMP 宿主扩展 | Clojure/JVM 独立服务端 + CLJS/Fulcro/F7 瘦客户端 | 彻底摆脱宿主私有生命周期、隐式上下文压缩与工具审批弹窗干扰 |
| 规划产物 | 阶段文件曾混入状态目录或固定名 | 唯一用户可见底稿 `plans/<name>.md`，首评前只读 | 底稿是给人类审阅的正式交付物，不是内部崩溃恢复日志 |
| 施工期组织 | 中心化 `Manager` 派单给最多 4 名 `Coder` | 中心化 `Reviewer` 立账 $\to$ 去中心化 `Swarm` 围绕 STM 看板自治施工 | 消除单点导演同时汇总十几个子代理上下文的通信与注意力瓶颈 |
| 文件并发写 | 靠角色职责软约束或事后 git 合并 | 批内内存视图整批 Dry-Run + `read-set` `(mtime, size)` 复核 + 写入口 $W$ 原子落盘 | 在触碰真实磁盘前拦截一切普通冲突与定位歧义，正常路径零回滚 |
| 黑盒 Shell | 独立 `devops` 角色跑命令 | 唯一令牌持有者 `Swarm Holder`（`take-or-die` 竞争，无责即死，仅在 `.call` 期间占 $W$） | 动态承接全看板验证责任；持令等待期间不占写锁阻塞普通编辑 |
| 工具与数据语法 | JS 函数体 + CUE 字面量 | Clojure `(defprogram ...)` + EDN + UFSS（`#"..."#` / `#"""..."""#`） | 消除 JSON 双重转义与 CUE 的 7 类文法陷阱，与 Clojure 同像（Homoiconic）底座天然合一 |

### 9.2 为什么从 CUE 演进到 EDN + UFSS（反面文法教训存档）

早期版本曾尝试用 CUE 替代 JSON 作为面向大模型的结构化格式，实测暴露出大模型极易踩中的 **7 类 CUE 文法陷阱**，成为万象术设计 §6.3 **EDN + UFSS（通用变长围栏字符串）** 的直接动因：
1. **隐式字符串插值陷阱**：CUE 在普通双引号与多行 `"""` 中遇到 `\(...)` 会触发表达式插值报错，迫使带反斜杠的源码必须记复杂的井号配对，而 UFSS 的 `#^n"..."#^n` 与 `#^n"""..."""#^n` 具备**彻底零转义（True Raw Semantics）**，反斜杠永远只是字面字符 `\`。
2. **列表元素跨行漏逗号**：CUE 映射字段换行可省逗号，但列表 `[a\n b]` 换行省逗号立即语法报错；EDN 中空白、换行与逗号完全等价，列表与向量天然无需逗号。
3. **特殊字段名漏引号**：CUE 中含连字符、点号、斜杠或数字开头的键（如 `foo-bar`、`src/a.clj`）不加引号即被当成减法或路径表达式报错；EDN 关键字 `:foo-bar` 或字符串键无此歧义。
4. **单行以 `"` 开头或结尾的定界冲突**：在朴素围栏中，若正文以 `"` 结尾（如 `foo"`），写成 `#"foo""#` 会看花眼甚至被错误词法器截断；UFSS 利用无真前后缀重叠定理（$\pi(\text{"}\#^n)=0$），从数学上保证首个 `"#^n` 必为唯一定界符。
5. **保留字冲突**：CUE 中 `null`/`true`/`false` 作无引号键名会冲突；EDN 中关键字 `:null`/`:true` 与布尔值 `true`/`false`、字符串严格正交。
6. **多行块缩进混用 Tab 与空格或前导空格不足**：UFSS 明确定义五步确定性缩进剥离算法，并在非空行前导空格少于结束行 `<indent>` 时显式报错 `:UFSS_INDENT_VIOLATION`，绝不静默吞字。
7. **空值制造空洞约束**：CUE 中填 `null` 会变成类型约束而非缺省；EDN 中 `nil`、空集合 `[]`/`{}` 与缺省键语义一目了然。

### 9.3 核心设计决策汇总台账（D01–D16 及全栈架构裁决）

| 编号 | 决策主题 | 采纳方案 | 明确拒绝的替代方案与理由 |
|---|---|---|---|
| **D01** | 工作区拓扑 | 单一真实共享工作区 + 受保护提交与写入口 $W$ | 每个个体一套私有 git worktree / 分支合并（把协作延迟到事后合并，无法及时暴露接口变化且破坏单一现场） |
| **D02** | 任务调度权 | 无全局任务选择者，个体自主观察 STM 看板拆分与认领 | 中央调度器按模型画像派单（引入单点瓶颈与隐式偏见，违背去中心化自治） |
| **D03** | 复合任务处理 | 拆分优先于原子执行认领，拆分本身即直接贡献 | 先认领大任务再在私有上下文里慢慢做（导致长期间锁死大范围工作，转世后无法接续） |
| **D04** | 父项完成条件 | 父项始终保留实质工作，子项全完成只使父项从 `:waiting` 恢复 `:ready` | 子项完成计数等于总数时自动将父项标为 `:done`（抹杀接口合龙、集成核验与目标复核实质） |
| **D05** | 普通与令牌归属 | 普通任务归 `owner-id`；`:needs-token` 任务动态归当前唯一 `holder-id` | 抢到令牌时逐条扫描改写所有令牌任务的 `owner-id`（制造无谓写冲突且漏掉并发新增项） |
| **D06** | 令牌竞争结局 | `take-or-die` 只有赢（升为 `holder`）或输（同事务公开移交剩余责任并逻辑死亡） | 抢令牌失败后退回继续当普通个体（破坏“耗尽受保护工作才参选”的严肃边界，诱发盲目轮询抢令） |
| **D07** | 持令者退出条件 | 完整责任集合 $R(\text{holder}) = \emptyset$ 时在同一 STM 事务内立即释放令牌并逻辑死亡 | 设固定持令时长上限或允许持令者无责后降级回普通个体（制造带前世包袱的特权长寿个体） |
| **D08** | 槽位转世记忆 | 死亡后销毁旧上下文，新 `agent-id` 仅凭公开看板与现场启动 | 向新生个体注入“你上一世刚刚抢令失败/改错代码”的私有总结（污染新视野，违背无记忆转世） |
| **D09** | 文件变化判据 | 仅用系统元数据 `stamp = {:mtime-sec :mtime-nsec :size}`（rsync quick check） | 引入 `atime`（只读即触发假冲突）或维护全局文件内容哈希历史表（增加锁外扫描负担与第二套版本真相） |
| **D10** | 读取锁策略 | 普通读取（`.cat`/`.stat`/`.ls`）不加锁，仅在提交时于 $W$ 内复核全部 `read-set` | 读取也申请共享锁或快照锁（让耗时 Shell 阻塞全员阅读与思考） |
| **D11** | 普通文件写入 | 批内内存视图完成整批 Dry-Run + 持 $W$ 复核 `read-set`（含只读依赖）后原子落盘，正常路径无回滚 | 边执行脚本边直接改真实磁盘，出错再靠 git checkout 回滚（可能误伤他人已提交成果或留下半截脏文件） |
| **D12** | Shell 与 $W$ 关系 | 令牌决定身份责任，$W$ 仅在实际运行 `.call` 会话或文件提交期间持有 | 持令者在整个持令生命期内独占 $W$（导致持令者等修复时全员无法提交文件） |
| **D13** | 槽位主动休眠 | 只要全局非阻塞可推进集合非空，严禁任何个体 `park-slot`；检查与登记同事务 | 个体看自己暂时没活就休眠（导致需要拆分或协作的公共工作无人理睬，或检查与休眠分离漏掉唤醒） |
| **D14** | 停电与崩溃恢复 | 控制状态走持久事务与 `:report-pending`；外部副作用未知标 `:outcome-unknown`，文件硬失败走 best-effort 核对 | 承诺断电后跨文件系统与外部进程自动回滚或自动重放未确认外部写操作（导致重复副作用或覆盖新修改） |
| **D15** | 会话状态并发 | 单 Owner 串行状态入口 + Dijkstra 级触发守卫命令 + 外部 I/O 并行 | 多线程直接争抢会话状态配业务层 OCC 重试，或用深层嵌套 `while` 循环把模型流、工具与重试绑死在同一调用栈 |
| **D16** | 模型与前端协议 | 仅用 LangChain4j 低层 `ChatModel`/`StreamingChatModel`；前端 Fulcro + Framework7 经 Sente 跑内部 AG-UI | 使用 `AiServices`/`ChatMemory` 托管历史（剥夺内核对 Scope、Pair Hint 与重试的精确控制）或前端无条件浅 merge 快照 |

### 9.4 前期打包与 Linux 平台实测工程教训（源自 `a0lite` 档案）

以下记录早期将 Agent Zero 封装为轻量 Flatpak（App ID `io.github.agent0ai.a0lite`）过程中实测验证的平台构建、容器瘦身与系统服务排障事实（作为 §7.2 环境供应与 `HACK.md` 平台限制的参考基线）：

1. **运行环境分层与宿主机服务复用**：
   * 将稳定的基础运行时（BaseApp）与频繁迭代的业务层分离，构建时开启 `keep-build-dirs` 缓存编译目录，日常改代码仅重编最上层代码。
   * **宿主机 Ollama 零拷贝复用**：不将数十 GB 的模型权重与推理引擎重复打包进沙盒，而是直接连接宿主机已运行的 Ollama 服务（`http://127.0.0.1:11434`，模型权重共享宿主机 `~/.ollama/models`）；当容器内预置指向本地内嵌实例的默认配置时，在启动入口将其统一重定向至宿主机服务地址。
   * **剔除低频重型依赖**：移除默认捆绑的 SearXNG、Playwright 浏览器镜像、本地 Whisper/Kokoro 语音大模型、torch CPU 全量包与冗余静态文档，仅保留高频核心工具（`git`、`ripgrep`、`fd`、`uv`、`ffmpeg`、OpenSSH 客户端等），使单文件分发包体积从数 GB 降至 **`367 MB`**。
2. **Flatpak / OSTree 构建与单文件打包性能实测**：
   * `flatpak-builder` 在收尾阶段执行 debuginfo 剥离（strip）与本地 OSTree 仓库提交是固定重尾耗时，但**打包产物必须保持未解压文件目录形态**供 OSTree 内容寻址，不得为省构建时间将其预先打成内部归档（否则破坏 OSTree 块级去重与增量更新）。
   * `flatpak build-bundle` 默认使用高压缩率 XZ 单线程压缩，实测耗时高达 **`1m44s`**（占全流程近半）；开发调试期间应直接通过本地 OSTree repo 安装验证（`flatpak-builder --user --install`），仅在最终发布单文件 `.flatpak` 时才执行 `build-bundle`。
3. **Linux systemd / NetworkManager `basic.target` 顺序环死锁排查备忘**：
   * 在启用自定义 dispatcher 脚本或第三方网络服务的 Linux 宿主机上，若出现“开机偶发无网络，手动重启 NetworkManager 即恢复”，需执行 `journalctl -b -u NetworkManager.service -u basic.target -g "ordering cycle| Breaking ordering cycle"` 检查是否存在 `basic.target` 依赖环（例如某服务未设 `DefaultDependencies=no` 却声明 `Before=network-pre.target`，导致 `basic.target -> ... -> network-pre.target -> sysinit.target -> basic.target` 闭环被 systemd 随机截断并跳过 `NetworkManager.service/start`）。在诊断万象术宿主网络不可达故障时，应区分此类宿主系统服务启动环路与模型供应商端点故障。

### 9.5 本轮裁决与逻辑链（2026-11-03）

本节记录本轮修订的裁决与理由，按“问题 → 裁决 → 理由 → 代价”组织。后续维护者若推翻某条，先读这里的代价，再决定是否值得改。

1. **Plan 接力后的继任者**：Plan 阶段的接力产生新 Plan tenure，执行阶段产生新 Reviewer。理由：底稿写权只归 Plan；若继任者是只读 Reviewer，S2/S3 无人能写。代价：两阶段的 relay 必须区分角色。
2. **屏障恢复**：退休、上下文屏障、继任者、里程碑复制在同一原子事务；删除“屏障已写、台账未推进”的窗口。理由：系统只承诺 best-effort 抗停电，不承诺 WAL 重放；KISS。代价：最后一次提交可能丢失，恢复后按现场重判。
3. **初任空账**：初任在 `执行` 首次空账且工作区已满足底稿时，不记 review，转普通 `ask`；`.unpark` 后回答只作新输入；同一初任只 ask 一次。理由：把“无工作”与“请求交接”分开，避免静默晋级，保留第二眼。代价：多一次模型往返。
4. **Plan 阶段不开蜂群**：Phase B 只属于执行阶段；Plan 的非空台账由 Plan 自己改底稿销账。理由：底稿只归 Plan；Plan 的义务是规划问题，不是施工。代价：Plan 不能并行施工，但 dedicated worker 仍可并行调查。
5. **devops 契约**：Plan 专属、无看板、无令牌的 dedicated worker；参数 `{:verbose-intent "..."}`；返回最终报告；无 `:DEVOPS_IN_FLIGHT` / `:PLAN_DEVOPS_DEAD`；只读文件面加 `call`。理由：只有一个 worker，不需要同步；传统 harness subagent 形态；报告是工作产物。代价：worker 死亡后由 Plan 新建，跨调用上下文可能丢失。
6. **API 形状**：所有能力为裸函数加单 map；无 `this`、无位置参数；`ls` 纯探查。理由：一种写法；模型不易记错；`ls` 只是发现，不是依赖。代价：全部示例与提示词要机械改写。
7. **看板状态与动词**：状态只剩 `:ready` / `:waiting` / `:done`；取消与替代是 `cancel` / `replace` 动词与事件，不是状态；只允许叶子；新增 `release` / `reopen` / `comment` / `update`。理由：KISS；避免不可达状态；专用动词保留各自前置条件。代价：取消子树必须自底向上逐个调用。
8. **令牌交接**：删除 `handoff-updates`；`take-or-die` 要求 `:confirm :i-hereby-confirm-i-die-without-regret`；交接说明用任意看板写入。理由：竞争前已要求写说明；失败事务是固定动作；确认令牌是 poka-yoke。代价：忘记写说明只能靠纪律与 Nudge。
9. **finish 顺序**：不 drain、不延后；程序顺序就是语义；`finish` 复核真实文件系统。理由：LLM 自己排布；系统不干预；停电只 best-effort。代价：LLM 可能写出“先 finish 后落盘”的错误程序，系统不替它兜底。
10. **编码方向**：系统到模型强制 EDN/UFSS；模型到系统自由，JSON 允许；传输层 JSON 信封不算呈现。理由：单向零转义；不强迫模型；provider 标准。代价：程序文本在传输层仍可能被 JSON 转义。
11. **Scope**：`:me-too`-only 默认 `:keep-last` 扣 1；删除 `:SCOPE_METOO_ONLY`；retention 单位是 `:keep-last` 结算次数。理由：罚款优先于抓人；避免整批拒绝。代价：预算为 0 时仍硬拒。
12. **前缀**：以渲染消息序列、语义白名单与 digest 链为准，不存完整请求；缓存 best-effort；新增看板写者 clean-break 边界。理由：与“不存完整请求副本”一致；digest 可验证；100% 不可承诺。代价：字节级漂移可能漏检。
13. **看板观察**：Pair Hint 推送；读者 delta、写者 clean-break 是**整张看板全量**（所有 `Task`/`Action`/`Actor`/`TokenSlot` 当前字段，不是注入视图）；旧 delta 清空腾出空间；读者超阈值也 clean-break；无 `(board ...)`。理由：保持前缀缓存；写者需要一致全量；旧 delta 的空间足以容纳全量。代价：看板极大时全量可能仍超上下文，需要运行时截断或压缩。
14. **多 worktree（互不相交的 fork/queue join）**：每个 swarm 一个 worktree；`$W$`、令牌、看板 per worktree；Steward 扩展为 orchestrator；按底稿 S3 拆成尽量互不相交的 N 个 slice，**不把同一个活派给多个 swarm**；disjointness 是工作分配层面，worktree 隔离就是边界，不检查路径；group 内部自治；fork N 自由、无上限；join 是队列（按完成时间 FIFO），每次取一个已完成结果，其余排队；队列空但仍有 running 时无限阻塞，队列空且无 running 时返回 `:none`；反复 join 直到 `:none`；没有 sibling 取消；每个结果 rebase 后 ff；master 集成锁；`收尾` 单任期 quiescence；worktree 在 join/abandon 后保留 7 天再 GC。理由：同一个活派给多个 swarm 是浪费；互不相交的拆分让 join 成为队列而不是竞速；master 线性。代价：拆分质量决定并行度；冲突靠 rebase 或 abandon。
15. **fission 删除**：整体移除同身裂变、泳道、收敛组、present 替换与相关错误码。理由：holder 裂变会破坏令牌语义；普通个体并行已有多个 worker；KISS。代价：失去同身并行。
16. **Plan 模型**：Plan 使用主模型；“Plan 模型”不是独立可配置槽位；§1.1 #8 优先。理由：铁律优先。代价：若未来要独立 Plan 模型，必须先改 #8。
17. **全文内联**：LLM 写的报告（join 结果、Plan dedicated worker、子任期）原样全文投递，不截断、不摘要、不转 artifact；只有非 LLM 的原始工具输出才按 §7.2 有界处理。理由：coding agent harness 的传统就是返回全文；LLM 的输出上限就是天然边界，不需要替它胆小。代价：Steward/Plan 的上下文可能变长，但不会超过模型自己的输出预算。
18. **rebase 不可解决即 abandon**：某个 slice 的 rebase 冲突不可解决时，该 slice 的 holder 调用 `(abandon {:reason ...})` 死亡，worktree 丢弃；该结果不入队，其余 slice 继续。理由：如果 rebase 都不可能，说明新版 master 已不需要这份工作；宣布 ff 失败或无限重试都不如直接放弃。代价：可能没有结果，Steward 需处理 `:no-results`。
19. **默认上限**：单文件 2 MiB、单批 16 MiB、单批 256 个路径操作、`call` 内联 1 MiB、`call` artifact 64 MiB。理由：代码文件远小于 2 MiB；16 MiB 批次足够多文件重构；256 个操作再多应拆分；1 MiB 与 Sente 单条消息上限一致。代价：超大生成文件需要走 Shell 或拆分。
20. **带状编辑距离**：`.where` 零匹配的 `nearest` 用 Ukkonen 带状 DP，$K=64$；长度差 $>K$ 直接跳过；先裁公共前后缀；只保留 $\le K$ 的行；无候选则 `nearest nil`。理由：不设上限会卡（2000 行 × needle × line）；$K=64$ 足够识别拼写/标点差异，差太远就是不相似。代价：超过 $K$ 的距离不精确；极端相似长行仍有 10M 单元预算上限。
21. **底稿命名**：`Plan` 自选 `plans/<name>.md` 的文件名；系统只校验安全、唯一与 `.md` 后缀；首次成功写入固定路径。理由：Plan 最了解任务，命名是它的事；系统不该替它发明名字。代价：Plan 可能起名重复或过长，系统必须给出明确拒绝码。
22. **不检查路径**：group 由 worktree 隔离；Steward 只保证目标不重叠，不检查 `:paths`；group 只被告知自己的 workspace，越界写入靠自觉。理由：worktree 是边界，路径检查既误报又增加 Steward 负担；系统不假装是安全沙箱。代价：越界写入不受协议保护，只按外部访问 best-effort 处理。
23. **fork/join 细节默认**：`fork` 返回 `[{:swarm-id :slice-id :workspace}]`；`join` 返回 `{:swarm-id :slice-id :workspace :report :ff-commit :rebase-rounds}`；队列按完成时间 FIFO；fleet 状态为 `:running`/`:queued`/`:joined`/`:abandoned`/`:failed`；`:result-queued` 事件带 `:swarm-id`、`:slice-id`、`:board-version`。理由：这些是命名与呈现细节，不改变协议；按最简、最可读的默认落。代价：若未来要按优先级而非完成时间 join，需要显式改这条。

## 2026-11-03 重述丢失的信息

### Plan worker 的只读边界

- 旧表述：`Plan` 全程只读；dedicated worker 的 Shell 被默认为只读。
- 当前规则：`Plan` 顶层代理只读；worker 的文件工具只读；`call` 是 best-effort 只读；非只读副作用不记为事故；若改变了调查依据，`Plan` 必须重新核对现场。
- 丢失的假设：worker 的 Shell 不会写文件。当前规格承认它可能写，但不再把“只读”宣传成保证。

### 交付身份

- 采纳：主要程序启动时打印 git hash 与 dirty 标志；这是唯一的交付身份校验。
- 拒绝：记录依赖候选、核对实际 classpath、隔离安装产物、检查包内容、构建缓存输入/输出核对。
- 理由：对当前项目这些是仪式；banner 足以防呆。

### devops 时序

- 采纳：`Plan` 阶段的 `devops` 同步、无并发；事件由单 Owner 串行；用户输入与报告的先后是物理顺序。
- 拒绝：`:observed-at` / `:workspace-stamp` 元数据、报告新鲜度重查规则。
- 理由：没有并发竞态；剩余只是模型注意力，不是协议洞。

### clean-break

- 采纳：先定义新接口、迁移所有调用方、删除旧本体、最后实现新本体；旧本体不得被依赖或委托；残留调用由编译/测试暴露；不加静态证明；不加“旧行为已消失”的测试；1.0.0 前不承诺行为或数据兼容。
- 拒绝：新旧并存、facade、双写、长期 feature flag、外部契约例外（1.0.0 前）、数据迁移、机械证明、幽灵测试。
- 理由：旧本体先删，新本体最后实现，物理上消除委托诱惑；1.0.0 前无兼容义务。

### fission

- 旧：同身裂变、泳道、收敛组、present 替换、相关拒绝码。
- 当前：并行只靠多个独立 worker；不存在同身并行通道。
- 丢失：为什么删除（holder 裂变破坏令牌语义；普通个体并行已有多个 worker；KISS）。

### D01–D16 台账

- 台账整体移入本文件；当前规格不再引用 D 编号。
- 特别记录：D01 是蜂群内（所有个体共享一个物理工作区，拒绝 per-agent worktree）；多 worktree 决策是蜂群间（每个 swarm 一个 worktree，master 只 ff，join 队列）。两者不是同一层，不能压成一条。
- 旧台账的“明确拒绝的替代方案”保留在本文件，不再作为当前规格的参照系。

### 其他旧表述

- `2026-11-03 修订` 的日期标记已从当前规格移除；当前规格不按日期叙述。
- “`fission` 已删除”“`handoff-updates` 已删除”等否定式历史标记已从当前规格移除；当前规格只描述现有能力。
- §9.5 的参考文献索引保留在 `AGENTS.md` 的 §9.1；它不属于历史流水，而是当前引用。
- 旧规格中关于“多服务进程部署时 `$W$` 仍代表同一个逻辑资源”“先把语义跑通再考虑跨主机”以及 `pending/AGENTS.md` 中“宿主事件粗通知 vs 精确结算双入口”“进程内互斥不能当跨进程保证”的过渡假设均已淘汰：万象术明确采用每机器单例进程（Per-Machine Singleton）+ 多并发 Root Sessions 架构，二次启动只向首实例发消息，进程内互斥与单写者即机器级权威。

## 2026-11-04 已关闭的 fleet app 装配缺口

### supervisor 装配

- 旧当前说明（已从 `src/hc/compose/fleet/AGENTS.md` 删除）：`fleet.supervisor/stop!` 已存在，但 app/system 尚未把 `:fleet-supervisor` 放入 system map，也未在 model ports/store 关闭前调用；因此 maintenance restore 的 supervisor quiescence 未获证明，late model events 只能在 `stop!` 后拒绝。
- 当前事实：`hc.server.app.system` 构造 `fleet-supervisor-instance`，把 `:fleet-supervisor` 放入 system map，并在 `system/stop!` 中先于 `:fleet`、model ports 与 store 调用 `fleet-supervisor/stop!`，传入按 attempt-id 取消模型请求的 `:cancel-attempt!`。

### launcher、model gateway 与 worktree 工具循环

- 旧当前说明（已删除）：app assembly 仍传入显式 `:SWARM_EXECUTOR_UNAVAILABLE` launcher；app 尚未按 worktree 连接模型网关与 clj-worker 工具循环。
- 当前事实：`system.clj` 把 `:launch-swarm!` 绑定到 `(:launch fleet-supervisor-instance)`；构造 `fleet-gateway`，由 `fleet-start-ref` 处理 `:model/start` 并记录 attempt；supervisor 的 `:execute-tool!` 使用 `fleet/worktree-runtimes` 调用 `mission-worktree/execute-program!`。

### worktree 工具执行器

- 旧当前说明（已删除）：app executors 仍 rooted at the session workspace；把 app tool executor 接到 `service/worktree-runtimes` 是剩余工具执行缺口。
- 当前事实：session Owner 的 `tool-exec` 在请求带 `:workspace` 且 `fleet/worktree-runtimes` 非空时，使用 `mission-worktree/execute-program!`；supervisor 的 `:execute-tool!` 也走同一 seam。`runtime-entry` 对非根 workspace 创建以该 workspace 为根的 runtime。

### holder 冲突解析器

- 旧当前说明（已删除）：app/system 仍传入 constant abandon resolver；必须传 `conflict/make-board-resolver` 与 `:service-ref`/`:git` 及真实 holder model loop。
- 当前事实：`system.clj` 构造 `fleet-holder-resolver`（`fleet-conflict/make-board-resolver`，带 `:git`、`:service-ref fleet-ref`、`:start-effects!` 到 supervisor、`:timeout-ms 300000`），并将其作为 `:resolve-conflict!` 传给 supervisor 与 `fleet/start!`；holder 效果经 supervisor `:start-effects!` 进入 fleet gateway。

### 保留理由

- 这些条目曾作为 application integration gap 记录在 fleet 局部文档；当前装配已直接取代旧否定式说明，故从当前规格删除，历史状态记录于此。

## 2026-11-05 会话删除持久化与 purge 权威

### 问题

- 现象：`:session/delete` 回执已 `:accepted`，但 committed sessions store 仍是 `:active`；live Owner 已是 `:deleted`。purge 在 stop-owner 前读 committed 记录，看到 `:active` 而拒绝；手动 stop/flush 后才变成 `:deleted`。
- 根因：compose Owner 的 metadata handler 只改自己的 owner-state 文档，从不把 session 写回 `SessionPersistence`；`sessions/apply-command` 也刻意不 bump revision。回执先于业务提交，committed record 永远慢一拍。
- 曾被尝试的错误修法：在 purge 前置条件里接受 live `:deleted`，并在 `registry/purge-session!` 里临时 bump revision 把 live deletion 写进 committed store。该修法让一个未提交的删除看起来合法，掩盖了 Owner 缺少 business commit 的事实。

### 裁决

- Owner 在 accepted receipt 之前，通过 `commit-owner-session!` 读 committed record、以其 revision 为 CAS token、bump 一次 revision 并提交 session；提交失败或未知时回执为 refused/unknown，绝不 accepted。
- Owner 启动时通过 `reconcile-owner-session!` 修复 owner-state 与 committed record 的滞后：live 较新则写回，committed 较新则采用 committed。committed record 缺失时拒绝启动，不复活已 purge 的会话。
- purge 只读 committed record；live-Owner merge 不参与删除资格判定。永久删除前 Owner 必须显式报告 `:stopped?` 与 `:drained?`；裸 `{:ok? true}` 不算 drain 证据。temporary discard 同规则。
- 删除 `persist-live-deletion!` 与 `purge-session!` 的 live-session 参数；不保留“用 purge 补写 deletion”的后门。
- `discard-session!` 的 `try` 体曾把 `{:ok? true}` 放在 stop 调用之后作为最后一个表达式，导致真实 stop 结果被丢弃、discard 永远看不到 `:stopped?`/`:drained?`。现在 try 直接返回 stop 结果；Owner 存在但返回 nil 时按 `OWNER_STOP_INCOMPLETE` 拒绝。

### 代价

- 每个改变 session 的 Owner 消息多一次 `SessionPersistence` CAS 提交；owner-state 提交失败时可能出现“session 已提交、回执 unknown”的窗口，客户端需 resync 读取已提交的 session。
- 旧版本遗留的“owner-state 已 deleted、committed 仍 active”记录不会在启动时自动修复，因为启动只为 committed 非删除会话创建 Owner；这类记录仍按 committed authority 拒绝 purge，需显式恢复或数据修复。

## 2026-11-05 Sente 出站批量与快照重发

### 问题

- 现象：E2E02 中 UI 停用连接已保存，持久状态为 `enabled false`，离线基线正确；但 live wire 的 `model-availability` 始终未确认。app 日志反复出现 `SENTE_MESSAGE_TOO_LARGE`，一次实际打包大小为 1,194,776 bytes，超过 1 MiB；随后 Sente 记录 `pack-failure`，快照没有送达浏览器。
- 根因：`hc.server.app.system/start-publisher` 每 500 ms 定时发布一次完整 `STATE_SNAPSHOT`，并在每个 Owner 通知后 25 ms 再发布一次。Sente 的 Ajax 传输把队列中的多个出站事件合并成一个 pack；单个事件都低于 1 MiB，但批量很快超过边界。`BoundedPacker` 拒绝整个批量，后续批量继续包含积压快照，形成反复失败。

### 裁决

- 应用发布器改为增量：与上一份成功发布的树比较；无变化（包括只有 `:server/now` 变化的 tick）不消费 feed seq；有变化且 `STATE_DELTA` 事件不大于完整快照时发送增量；完整快照只用于首次发布、订阅基线、resync 与增量不划算时。
- 发布失败把 gateway 的 `:SENTE_MESSAGE_TOO_LARGE` 等结果写入 `:publish-error`；delta 失败后回退完整快照时把原错误写入 `:publish-fallback`。1 MiB 边界不提高，也不把失败伪装成成功。
- `hc.server.gateway.diff` 只生成 `add`/`remove`/`replace`，与 `hc.server.gateway.patch` 和 `hc.client.sync.json-patch` 的 JSON Pointer 语义一致。

### 代价

- 每次 tick 仍需构建完整投影并做一次树比较；网络只传变化字段。
- 单个事件仍必须低于 1 MiB。更大的单条消息应按既有边界走独立 blob/分段通道，而不是删除消息正文或提高 Sente 上限。

## 2026-11-05 0.0.1 发布前的规则替换

### 备份 CLI 的加密默认值

- 旧实现：`offline/backup!` 无条件把 `:encrypt?` 传给 `core`，CLI 不传该键
  时得到 `nil`；`core/normalize-create-options` 看到键存在，把 `nil` 当成显式
  `false`，于是 CLI 默认备份不加密，密码策略也不执行。CLI 的 `select-keys`
  又没有 `:encrypt`/`:include-secrets`，`--encrypt true` 解析成
  `expected true or false {:value nil}`。离线实跑把这两项记为 D1、D2。
- 当前规则：缺省 `:encrypt?` 表示“未指定”，由 core 默认加密；CLI 把
  `:encrypt` 映射为 `:encrypt?`，只有显式 `--encrypt false` 才关闭加密；
  未加密备份包含秘密需要显式确认。`--include-secrets` 同样映射为
  `:include-secrets?`。
- 代价：依赖“默认不加密”的旧脚本必须显式 `--encrypt false`；默认路径现在
  强制密码长度与密码确认。复检见 `HACK.md` §13.4。

### 停止命令的 command-id

- 旧实现：客户端把本地递增的 `cmd-N` 同时用作 UI 排序键和 Owner 幂等键。
  页面重载或第二个客户端从 1 重新计数，与已接受的 command-id 相撞；Owner
  按“同 id、不同 payload”拒绝，停止请求无法送达。E2E 01 的回执原文是
  `command-id was already accepted with a different payload`。
- 当前规则：command-id 改为 `cmd-<random-uuid>`，跨页面重载与客户端唯一；
  本地 `:sequence` 只负责 UI 排序；Owner 仍拒绝同 id 不同 payload，不把冲突
  伪装成重放。
- 代价：回执 ID 不再可读为序号，排查时要按 UUID 关联；旧的 `cmd-N` 日志与
  脚本不再适用。真实点击停止路径尚未在 0.0.1 重跑，发布说明仍把它标为未
  复验。

### 离线恢复的目标 store

- 旧假设：CLI `restore` 可以在完全空目录中创建 store 并完成恢复。
- 当前事实：CLI `restore` 要求目标 store 已存在；对完全空目录报
  `STORAGE_NOT_FOUND`。当前流程先用 `app-check`、已有数据目录或
  `storage/open-store {:create? true}` 建 store，再执行恢复；无 app gate 的
  store 也可恢复，CLI 默认按 `:no-prior-app-commands? true` 处理。
  `--no-prior-app-commands` 已映射为 `:no-prior-app-commands?`，不再因键名
  不一致失效。
- 代价：离线恢复多一步初始化；发布说明不把“全新目录恢复”写成单命令。
  证据见 `HACK.md` §13.4 与 `plans/offline-restore-0.0.1.md`。

## 2026-11-05 spec 修订：用户体验、效率与读者分层

本节记录 `AGENTS.md` 本轮修订的取舍。按「条款 → 旧文 → 新文 → 依据 → 为什么」组织。

### 修改权限与 DEBT.md

- 旧文：开头写「AI 有权利和义务让各包局部规格说明和源码追上本文件，无需请示」与「本说明书未经允许不得擅自修改」相邻。
- 新文：明确「本文件只能由人类操作员修改；AI 改包局部 `AGENTS.md`、源码与 `DEBT.md`」；§8.1 增加 `DEBT.md` 的用途说明。
- 依据：`机制提炼/AGENTS.md` 的「工作与协作」§1 与「持久状态与恢复」§3；实现审计发现 spec 与实现存在 4 处已知缺口。
- 为什么：追赶 spec 的 AI 需要一个明确的工作队列；把「改 spec」与「追实现」分开，避免误改最高依据。

### 用户侧状态收敛为三种

- 旧文：Kelivo 参考手册 §2.3 的状态词表有 17 项（旧 §7.4 引用它；未配置、待处理、正在准备、正在连接、等待回复、正在生成、正在执行、等待你回答、等待批准、已完成、部分完成、已停止、未完成、结果待确认、已过期、正在重试、已中断）。
- 新文：用户侧只有「进行中 / 已交付 / 未交付（附理由与教训）」；24 类错误文案重组为「未交付的理由」分类；§1.1 增加「终局可解释」原则。
- 依据：用户裁决「用户的心智模型不一定要和实际发生的事情一致，允许善意误解；任务状态在用户侧只有正确交付或找到经验教训和理由」。
- 为什么：细分状态是内部实现细节，暴露给用户只会增加理解成本；用户只需要知道「做成了」或「没做成，为什么」。

### 不增加暂停态

- 旧文：§1.1 #5 只有三个合法停止出口；执行中用户只能 Esc 取消。
- 新文：明确「Esc 是取消，不是停止出口；取消后从当前现场重开 run 是唯一介入路径；系统不提供暂停态」。
- 依据：用户裁决「不需要暂停。这个类似一个 bot，你怎么能让一个活生生的 bot 暂停呢」。
- 为什么：agent 是活的生命周期（出生、工作、死亡、重生），暂停会产生「半死不活」状态，比取消更难排查。

### 不检测 agent 原地打转

- 旧文：§2.5 Nudge 只在回合结束时拦截。
- 新文：明确「Nudge 只判断回合是否结束，不判断是否原地打转；内核不评估进展质量」。
- 依据：用户裁决「不检测」。
- 为什么：符合「没有中心化调度者」的哲学；内核不替个体判断工作是否有效。

### 父项 `:ready` 无人认领不处理

- 旧文：§3.3 只说父项恢复为 `:ready`。
- 新文：明确「`:ready` 即公共可接续，系统不监控等待时长，也不因长期无人认领而升级或唤醒」。
- 依据：用户裁决「不处理：ready 就是可认领，总有人会看到」。
- 为什么：避免引入中心化监控；看板本身是公共事实。

### 用户可直接编辑底稿

- 旧文：§2.3 只说底稿是用户可见交付物，未定义用户编辑。
- 新文：「用户可直接编辑 `plans/<name>.md`；Plan 下次读取时看到用户改动，负责润色到与上下文一致，不得覆盖用户意图；`stamp` 冲突以用户版本为准」。
- 依据：用户裁决「用户可以改，如果用户改了，AI 负责润色到与上下文一致」；机制提炼「工作与协作」§2 记录旧实现有「等待用户决定」流程。
- 为什么：底稿是用户唯一能看见的规划产物；没有编辑入口，用户只能等 Plan 写完再提。

### `park` 是 agent 私有状态

- 旧文：§4.3 说 `park` 不落盘；§7.1 说重启扫描所有未结记录。两节之间有空隙。
- 新文：§4.3 与 §7.1 都明确「`park` 是 agent 私有状态，不属于看板；重启时运行时重置所有挂起程序，不恢复、不写入看板；扫描范围不含挂起程序」。
- 依据：用户裁决「park 是单个蜜蜂内部的事，和看板无关，重启则重置所有挂起程序」；实现 `program/runtime.clj:6-9` 自述不跨重启。
- 为什么：消除 spec 内部矛盾；追赶的 AI 不会误以为需要持久化续体。

### 精确匹配修改不设专用原语

- 旧文：§3.10 第 4 条写「带精确匹配条件的文本修改」为默认文件操作。
- 新文：改为「精确匹配的文本修改由 `where` + `subs` + `write` 组合实现，不设专用原语」。
- 依据：§4.1 能力表实际没有 `patch` 原语；§4.5 样例就是组合写法。
- 为什么：消除表述与能力表的不一致；保持「极简能力面」。

### 模型并发不本地排队

- 旧文：§5.4 只有重试分类，无并发上限。
- 新文：明确「模型并发不设本地排队；429 与短时 5xx 由有界退避重试处理；不引入容量租约、队列上限或本地信号量」。
- 依据：用户裁决「不排队，靠 429 + 退避重试」；机制提炼「宿主与模型调用」§5 记录旧实现设计了租约但生产调度器为空。
- 为什么：避免把旧实现的死代码搬进新系统；provider 自己的限流已足够。

### 数据库选定 Datalevin

- 旧文：§1 说「使用嵌入式数据库」；§7.1 列五个候选，无默认。
- 新文：§1 明确「Datalevin（LMDB 后端），单库多 partition，另设 blobs/artifacts/worktrees/fleet 四个文件区」；§7.1 候选对比保留为「不选的理由」。
- 依据：`deps.edn:56-58` 已选 `datalevin/datalevin 1.2.10`；`src/hc/server/storage/engine/datalevin.clj:64,71` 用 `open-kv` + `state` sub-DB。
- 为什么：实现已经做了决定，spec 需要追认；追赶的 AI 不需要重新选型。

### §7.4 按 8 段用户旅程重写

- 旧文：§7.4 按 8 组功能清单组织（信息架构、供应商、草稿、消息、历史、记忆、扩展、备份），约 18.0KB。
- 新文：按 8 段用户旅程组织（首次启动、日常对话、长任务、中断与恢复、历史与知识、扩展能力、导出备份迁移、设置与诊断），约 187.1KB。
- 依据：用户裁决「按用户旅程重写，但不能损失信息量」；旧结构要求读者跳到不同节才能拼出一件事的完整流程。
- 为什么：旅程是用户真实经历的顺序；功能清单是开发者视角。重写后每段是一个连续故事，前后依赖清楚。信息保全为 best-effort，已用脚本核对数值与编号。

### 22 条 E2E 全自动化 + mock 外部服务

- 旧文：§8.5 只列 22 条剧本与检查项，未定义执行方式。
- 新文：增加「22 条剧本全部自动化；配套 mock 外部服务（OAuth/WebDAV/S3）；mock 模型服务；手工验收只用于无法覆盖的平台差异」。
- 依据：用户裁决「提供配套的 mock 版本外部服务」；实现审计发现当前只有 `plans/e2e-0.0.1-runbook.md` 手工手册，无自动化入口。
- 为什么：不可重复执行的验收等于没有验收；mock 服务让 22 条剧本可以在 CI 里跑。

### 存储写入 fail-fast，不设第四态

- 旧文：§7.1 只有启动时的存储损坏保护；§5.2 的终态集合没有「存储不可用」；DEBT.md D-02 曾把四态列为待裁决。
- 新文：§7.1 明确存储是单点关键组件，写入只有成功一个终态；启动打不开拒绝启动，运行中任何写入失败立即终止程序，不重试、不降级、不继续派发；读取失败同样终止。§5.2 注明存储失败不进入 `:accept-call-terminal` 终态集合。DEBT.md 删除 D-02。
- 依据：用户裁决「存储是关键组件，就是成功一个态，否则 fail-fast 终止程序」。
- 为什么：存储坏了之后继续跑，只会产生更多不一致状态；终止比带病运行更安全，也更符合「当前态是唯一权威」。

### 未采纳：暂停、循环检测、父项监控、模型排队、`patch` 原语

- 依据：逐条用户裁决。
- 为什么：这些机制都会增加中心化判断或运行状态，与「agent 是活物」「没有中心化调度者」的哲学冲突；保留在 HISTORY 中，供未来重新评估。

### 本轮验证记录

**基线**
- HEAD：4915 行 / 763917 字节。
- 阶段 A/B 后的合并基线：4924 行 / 766764 字节。
- 合并后：AGENTS.md 6326 行；7 处引用修补后为 940327 字节。

**三路验证**
- 信息保全（verify-preservation）：旧 §7.4 的 UX-01..UX-29 编号全部可在新 §7.4 检索到；20 个附件、100 MiB、2048px、4.5:1、3:1、80%–200%、44×44、32×32、30 天、12 字符、7+4+3、2000 字、5 分钟、24 类、2 / 3、360×800、820×1180、1280×800、360px、100%、7 天均保留；无缺失。唯一修补：UX-17 工作区文件面板改为指向参考手册 §6.1/§6.2。
- 引用检查（verify-references）：全文 717 处 `§x.y`，除 `HISTORY.md §9.4` 外均可解析；修补 7 处：UX-17 指针、旅程 8 的 3 处 Kelivo 引用、附录 H1 换行、附录编号对照、旧 24 类表标注。
- 状态词（verify-status-words）：§7.4 范围内无旧状态词被当作标签；24 类错误文案重组为 10 类，映射表 24/24；8/8 旅程均有「本段覆盖的旧条款」。

**最终验收 12 项**
- 第 4 行含「本文件只能由人类操作员修改」。
- `终局可解释`=1；`DEBT.md`=2；`E2E 执行方式`=1；`模型并发不设本地排队`=1。
- `^#### [1-8]. `=8；`^### 7.4 前端工作台`=1；旧八组编号标题=0。
- `**编号对照**` 标题=1；旧 24 类表标注=1；`write-test-1500`=0；`^#### 7.4.`=0。
- §7.4 正文（1326–2778 行）内「八组」出现 0 次；附录中的「八组」仅出现在编号对照说明与旧组号引用中。

**限制**
- 结构检查只证明字符串存在，不证明 16 处条款语义正确或 §7.4 信息保全完整；信息保全为 best-effort。
- DEBT.md 已删除 D-02；存储写入按 §7.1 fail-fast 处理。
