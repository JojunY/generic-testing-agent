# 代码审查第 2 轮：核实与修复计划

> 范围：`origin/main`（PR #1 合并后）及 PR #2 分支的全部代码。
>
> 方法：先对照代码逐条核实审查意见，再修复，最后用单元测试和 `evals/e2e/run.sh` 验证。
>
> 状态说明：
> - ✅ 已修复，并有测试覆盖；
> - ➖ 核实后判定为设计如此，只修正了文档；
> - ⏳ 待决定。

## 1. 主要问题（15 条，全部属实）

| # | 问题 | 核实结论 | 修复 | 验证 |
|---|---|---|---|---|
| 1 | 批准正则使用 `find()`，"不批准 …""don't approve …"都会被记为批准 | 属实 | 批准必须是整条回复：`^批准|approve <flow>@R<n>$`。其他带"批准/approve"的回复不当作批准，而是当作修改意见退回 analyst | `AnswerParserTest`，`EngineTest`（否定式批准） |
| 2 | Maven 超时抛出可重试异常，状态未保存，挂起的测试会被无限重提 | 属实 | `MavenRunner` 把超时记为 `RunResult.timedOut`；`submit` 按 `TEST_TIMEOUT` 计入次数，同样失败两次即交给人 | `EngineTest.shouldCountATimedOutRun…` |
| 3 | 会话正常结束后，TRIAGING 条目被重置为 PENDING，稳定性失败永远不会被分流 | 属实 | 只有接管或空闲超时才重置条目，而且只重置 AUTHORING/REPAIRING | `EngineTest.shouldKeepItemsWaitingForTriage…` / `…ResetOnlyEditingItemsOnTakeover` |
| 4 | 标记 stale 时没有刷新快照；快照缺失时，人的答复被当作 agent 违规 | 属实 | gtaw 对 spec 的每次写入都经过 `writeSpec`，并自动刷新快照；快照缺失时以 `git show HEAD:` 的已提交版本为基线 | `EngineTest`（批准流程），e2e |
| 5 | 提交后、批准前被改动的 spec 仍会被批准 | 属实 | 批准前比对快照，不一致则拒绝批准，退回 analyst 并生成新版本号 | `EngineTest.shouldNotApproveASpecEditedAfterItWasShown` |
| 6 | oracle 分片解析失败时被当作 `{}`，会覆盖其他锁定项，门禁也失效 | 属实 | 失败即关闭：报 `CONFIG_ORACLE_SHARD_INVALID`，门禁报 `GATE_SHARD_UNREADABLE` | `OracleShardTest`，`GateTest` |
| 7 | `relock` 沿用旧 tokens，没有加锁 | 属实 | 在 `WorkLock` 内从 spec 的 THEN 重新推导 tokens，并更新 scenarioRevision；非场景 id 直接拒绝 | `EngineTest.shouldRelockOnTheSpecsExpectedValues` |
| 8 | `submit --review` 不需要 token | 属实 | review 节点发放 token；裁决必须带上当前 token，且一轮只接受一次 | `EngineTest.shouldRefuseAReviewVerdict…` |
| 9 | `lastSeenAt` 在命令开始时记录，长时间运行的 submit 会被误判为空闲 | 属实 | 改为在 `StateStore.save` 时记录 | `StateStoreTest` |
| 10 | spec 循环没有上限；`maxQuestionsPerRound`/`maxClarifyRounds` 未使用 | 属实 | 新增 `spec.maxSubmitAttempts`（默认 3），超出即 escalate；每轮最多 5 个问题；澄清轮数用完后不得再提新问题 | 单元测试，`GtaConfigTest` |
| 11 | 人选择"重试"后，checks 没有重置 | 属实 | 重试时同时清零 checks | `EngineTest.shouldGiveARetriedItem…` |
| 12 | 没有 sha256 时 wrapper 仍会下载并运行 jar | 属实 | `gtaw`、`gtaw-download.ps1` 失败即关闭；`doctor` 新增 wrapper 检查 | `sh -n`，doctor |
| 13 | 只要提交信息带 `Gta-*:` 就被认为是 gtaw 的提交，可以伪造 | 属实 | 新增提交账本 `CommitLedger`，只信任 gtaw 自己记录的 SHA | `ChangeCheckerTest.shouldNotTrustAForgedGtawTrailer`，`EngineTest` |
| 14 | `abort` 会删除 `recover/` 里唯一的备份 | 属实 | `abort` 保留 `recover/`，并在输出中说明 | 代码审查 |
| 15 | upgrade 不处理新版本不再分发的文件 | 属实 | 未被改动的旧文件直接删除；本地改过的保留并在输出中列出 | `InstallerTest` |

## 2. 规则违反（对照 AGENTS.md）

| 规则 | 结论 | 处理 |
|---|---|---|
| 所有写文件都经过 `AtomicFiles` | 属实，有 3 处未遵守 | 快照、`maven.log`、备份（新增 `AtomicFiles.copy`）都已改为经过 `AtomicFiles` |
| 不硬编码路径 | 属实 | 新增 `GtaPaths.snapshots/gtaResults/verifyLatest/gateLatest/commitLedger`；escalation 路径和 `testSourceRel` 改为统一使用；删除重复的 `Briefs.itemKey` |
| 新行为需要单元测试 | 属实 | 新增 `FakeTestRunner` 和 `EngineTest`（14 个用例），以及 `IsolatedEnvTest` 等；单元测试从 42 个增加到 80 个 |
| 错误码各自带提示 | ➖ 部分属实 | 提示是在抛出 `GtaException` 时必须给出的（这是设计）。AGENTS.md 的措辞已更正；`retryable` 改为按错误码覆盖（`STATE_BUDGET_EXHAUSTED` 不可重试）；concern 类别退出码为 0 也写入了规则 |

## 3. 架构

| 问题 | 处理 |
|---|---|
| `EngineContext` 存在循环依赖（ItemWorkflow ↔ SessionEnd，SessionManager → ItemWorkflow） | `dispatch` 返回 `Dispatch.sessionOver`，由 Engine 调用 SessionEnd；`planItem` 移到 `StateStore`。循环已消除 |
| 缺少"测试运行组件" | 新增 `run.TestRunner` 接口（Maven 实现，测试中用 fake）和 `engine.TestRuns`（缓存 JavaIndex，每次调用只解析一次） |
| AGENTS.md 与代码描述不一致 | 已更正，并写入新规则：账本、`writeSpec`、失败即关闭 |
| 状态、类型、裁决都用字符串表示 | ⏳ 建议放到 P1：需要迁移持久化的 JSON，风险大于收益 |

## 4. 效率与死代码

- **效率：**
  - JavaIndex 缓存；
  - `syncPlan` 中分片只读一次；
  - documented 降级只 lint 一次，并按场景 id 匹配（修复了按标题子串误匹配的问题）；
  - `commitsSince` 只调用一次 git；
  - spec 路径正则预编译。
- **死代码，已删除：**
  - 不可达分支；
  - `ENV_MAVEN_UNAVAILABLE`、`POLICY_PROTECTED_PATH`、`GATE_KNOWN_DEFECT_MISMATCH`；
  - 无用配置 `spec.source`、`environments.allowCloud/gcpProject/region`；
  - `WorkState.sessionRuns`、`Engine.paths()/config()`、`GtaPaths.session()/skills()/mainResources()`。
- **重新启用：**
  - `STATE_NOTHING_PENDING`：用于 `answer`；
  - `dispatch`：写入节点输出，供 orchestrator 使用。
- **全限定类名：** 已统一改为 import。
- **配置：** 未知字段报 `CONFIG_INVALID`，允许 `$schema`。schema 与代码对齐（`mutation.mode` 的取值原先写错）。

## 5. 小问题

- **全部已修复：**
  - `confirmedPin` 改为读取已提交的记录；
  - `commitItem` 不替开发者提交其已有的未提交改动（如 `pom.xml`），结果中会说明；`src/test/` 下由同一 flow 前面条目留下、尚未提交的测试代码照常提交。最初的实现连共享测试类也跳过了，是 e2e 的重建场景发现的；
  - spec 被删除或未批准时，门禁报 `GATE_SCENARIOS_UNLOCKED` concern；
  - `gate waive` 日期非法时报用法错误；
  - `status`/`report` 只读，不会移走文件；
  - `oracleReview` 设为组织底线，仓库不能关闭；
  - 问题数和轮数加上下限。
- **额外发现并修复：** 批准阶段回复修改意见原本会报"无法解析"，现在作为修改请求退回 analyst。

## 6. 目录与命名

- `evals/e2e/agent/*.method.java` 和 `*.head.java` 改名为 `*.java.txt`。它们是代码片段，IDE 不应再当作源码。
- `plan.md` 的状态从"设计定稿（未实现）"改为"P0 已实现"。§11.3 目录树已与实际一致。
- 删除了 CI 中重复的同步检查步骤，`RepositoryHarnessTest` 已经覆盖。
- **残留文件：** 工作区干净，没有缓存、日志、IDE 文件或 TODO。忽略的 `target/` 不在仓库内。

## 7. 待你决定（⏳）

1. **提示语语言。** "批准""其余默认"和 plan.md 是中文，其他文档是英文。
   - A. 保持现状：中英文都接受；
   - B. 提示语改为可配置 `ui.language: zh|en`，默认中文；
   - C. 统一为英文。
   - 建议 **B**，各部门都能用。
2. **groupId 和包名 `com.gta`。** 需要换成公司拥有的域名（如 `com.<company>.gta`），需要你提供。改名涉及 Nexus 坐标和所有服务的依赖，越早改越好。
3. **图示改版（diagram-design 风格）。**
   - **范围：**
     - 只改 README 和 `docs/design/plan.md` 中用于说明的图：架构总览、`next→dispatch→check→submit` 循环、条目状态机、spec 生命周期。
     - spec 里的 Mermaid 图是产品的一部分：解析器和 linter 会读取，PR diff 也要求是文本，所以保持不变。
   - **做法：**
     - 采用 cathrynlavery/diagram-design 的风格：五色调色板，含明暗两套；只有一个强调色；4px 网格；无阴影；字体为 Instrument Serif / Geist / Geist Mono，离线时用系统字体。
     - 产出静态 SVG，放在 `docs/diagrams/`，README 和 plan.md 用图片引用（github.com 不渲染内联 HTML/SVG）。
   - **drawio-skill：** 只作参考，其强项是可编辑的 `.drawio` 和代码导入，这里不需要。
