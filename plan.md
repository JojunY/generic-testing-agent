# Generic Testing Agent (GTA) — 设计方案

> 状态：设计阶段（未实现） · 版本：plan v2 · 日期：2026-11
> 范围：面向 Java（Spring 为主）服务的通用测试 harness，同时支持 GitHub Copilot 与 OpenCode 两个平台
> 命名空间：本项目所有对外资产统一使用前缀 `gta`（Generic Testing Agent）

---

## 0. TL;DR

1. **目标**：衡量测试的标准是"能不能发现错误"，不是 coverage。质量信号用 mutation score、catching test、flow 覆盖、oracle 强度、契约覆盖和 flaky 率（§1）。
2. **架构**：采用 **Blueprint = 确定性节点 + agentic 节点** 的模式，也就是 Anthropic 所说的 workflow + agent，Stripe 的 Minions 也是这样做的。
   - 流程推进、构建、执行、解析、门禁全部交给确定性 CLI `gta`（toolkit）。
   - LLM 只负责需要判断的环节：理解 flow、写测试、评审测试。
3. **Agent 少，Skill 多**：手写的 agent prompt 只有 4 个。按服务类型划分的 5 个 specialist agent 由**一个模板 + capability registry** 生成，所谓"dynamic agent"就是这样实现的。技术知识全部写成 `gta-*` skills，按需加载。
4. **验证独立于作者**：写测试的 agent 不能自己判定"通过"。由 `gta` 的确定性 verifier 加上只读的 `gta-reviewer` 来裁决，这一做法参考了 Spotify Honk、Uber AutoCover 和 Meta JiTTests。
5. **状态落盘**：被测仓库的 `.gta/` 目录存放 JSON 状态（计划条目只允许通过 CLI 改状态）、追加式进度日志，并且每完成一个计划条目提交一次 commit。这是 Anthropic 长时运行 harness 的做法。
6. **双平台**：同一份 `core/` 生成两个发行目录 `dist/copilot/`（Agent Plugins 1.0 布局）和 `dist/opencode/`（`.opencode/` 布局）。Skills 遵循开放的 Agent Skills 规范，两个平台用同一份内容。
7. **工程化**：统一命名规范（§7）、JSON Schema 契约（§8）、lint/build 校验、SemVer 版本管理、lock 文件、hooks 加 CI 双层护栏（§10），以及用 evals 回归 harness 本身（§11）。

---

## 1. 问题定义与质量信号

**现状**：服务都是 Java。部署在 GCP（GKE、Dataflow、Cloud Run）以及若干非 GCP 环境。flow 有 API、Scheduler、Batch、Workflow、事件驱动等多种。目标是做自动化 regression test 和 integration test。

**只看 coverage 不够的原因**：没有断言的测试也能贡献 coverage；coverage 不感知业务语义，例如边界值、幂等、时间、并发、最终一致性；也不感知跨服务契约。Meta ACH 被采纳的 571 个测试中，有 277 个按 line coverage 标准会被丢弃，但它们能抓到真实故障。

| 信号 | 定义 | 计算方式 |
|---|---|---|
| Mutation score（变更代码） | 对改动代码注入缺陷后，被测试发现的比例 | `gta mutate`（PIT，只针对 diff） |
| Catching 命中 | 针对本次变更生成的测试中，在新代码上失败且经确认为真实缺陷的数量 | PR 模式（§4.2） |
| Flow 覆盖 | flow catalog 中每条 flow 的 happy path、错误分支、边界是否都有测试 | `flow-catalog.json` ↔ JUnit/Karate tag |
| Oracle 强度 | 断言是否验证业务结果（状态、副作用、输出数据） | `gta lint-tests` + `gta-reviewer` |
| 契约覆盖 | OpenAPI operation × status code、消息 schema 是否覆盖 | Schemathesis / Pact / SCC 报告 |
| Flaky 率 | 新测试连续 k 次运行全部通过的比例（pass^k） | `gta run --repeat k` |
| Coverage | 仅作参考，不作为门禁 | JaCoCo |

---

## 2. 业界做法与设计原则

### 2.1 大公司的做法

| 来源 | 做法 | 本设计采纳的点 |
|---|---|---|
| **Meta ACH**（FSE'25） | 先生成少量针对具体风险的 mutant，再让 LLM 写能杀死它的测试；用 LLM 判定等价 mutant | Mutation 驱动补测（`gta mutate` → reviewer → 新计划条目） |
| **Meta JiTTests**（2026） | 每个 diff 推断意图，列出风险，据此生成 mutant 和 catching test。在新代码上失败的测试只算"weak catch"，先经规则和 LLM 双层 assessor 过滤，再交给工程师（human-on-the-loop） | PR 模式引入 `intent=catching`。失败的测试不会被"修绿"，而是进入 reviewer 判定后再上报 |
| **Uber AutoCover**（ICSE-SEIP'26） | 用 LangGraph 子图组织 prepare / generate / execute / validate-repair 四步；门禁看 coverage 增量和 mutation；多次运行防 flaky；高并发 | Blueprint 节点划分、pass^k 防 flaky |
| **Stripe Minions** | Blueprint 中确定性节点（lint、test、format）与 agentic 节点（实现、修 CI）交替出现；CI 修复最多重试约 2 轮，之后转人工 | 确定性节点交给 `gta`；每个条目最多重试 2 次 |
| **Spotify Honk** | 根据组件内容自动激活独立 verifier，agent 看不到 verifier 内部实现；通过 stop hook 在开 PR 前强制跑；最后由 LLM judge 对比 diff 与原始意图 | Verifier 按 registry 自动激活；stop hook；`gta-reviewer` 充当 judge |
| **Airbnb 测试迁移** | 分步流水线，每步都有校验；失败时把完整错误回传让 LLM 重试；上下文里加入同级测试和示例 | Specialist 的上下文包括同级测试和模板；`gta run` 返回结构化错误 |
| **Microsoft code-testing-generator**（dotnet/skills，2026-07） | 以 plugin 形式提供 agent + skills：研究仓库 → 计划 → 编写 → 验证；不修改生产代码；同模型下比原生 Copilot 高约 13 个百分点（92.1% vs 78.9%） | 验证了"专用 agent + skills"优于通用 agent；禁止改生产代码 |
| **Diffblue Testing Agent** | 与 Copilot / Claude Code 集成，只交付编译通过且运行通过的"verified tests" | 只有通过确定性验证的测试才会被提交 |

### 2.2 平台与 Anthropic 指南要点

| 来源 | 要点 |
|---|---|
| [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) | 能用 workflow（预定义路径）就不用 agent；常用模式有 routing、orchestrator-workers、evaluator-optimizer |
| [Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) | 由 initializer 生成 JSON 功能清单（只允许改 `passes` 字段）和进度日志；每个 session 只做一个条目，结束时 commit；固定启动流程是"读进度 + git log → 选最高优先级未完成条目 → 先跑基线检查"；JSON 比 Markdown 更不容易被模型误改 |
| [Multi-agent research system](https://www.anthropic.com/engineering/multi-agent-research-system) | Orchestrator-worker 架构，token 消耗约为对话的 15 倍，所以只用在可并行的宽任务上；给子 agent 的委派必须写清目标、输出格式和边界 |
| [Context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) | 按需（just-in-time）加载上下文；子 agent 在干净窗口中工作，只返回 1–2k token 的摘要；结构化笔记落盘 |
| [Writing tools for agents](https://www.anthropic.com/engineering/writing-tools-for-agents) | 少量高价值工具；工具名带命名空间；返回有意义的字段；提供 `concise/detailed` 两种输出；错误信息要可执行 |
| [Skill authoring best practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices) | `name` ≤64 字符，只用小写字母、数字和连字符，推荐动名词；`description` ≤1024 字符，第三人称，写明"做什么 + 何时用"；正文 <500 行；引用只深一层；超过 100 行的引用文件加目录；脚本要"自己解决问题而不是丢回给模型"；每个 skill 至少 3 个 eval |
| [Demystifying evals](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents) | 基本概念：task / trial / grader / transcript / outcome；按结果评分而不是按工具调用序列；区分 pass@k 与 pass^k；capability eval 与 regression eval 分开；从 20–50 个真实失败案例起步 |
| Copilot：[custom agents](https://docs.github.com/en/copilot/reference/custom-agents-configuration) / [hooks](https://docs.github.com/en/copilot/reference/hooks-reference) / [plugins](https://docs.github.com/en/copilot/concepts/customizations/plugins) / [subagents](https://code.visualstudio.com/docs/copilot/agents/subagents) | 见 §9.3 平台映射 |
| OpenCode：[agents](https://opencode.ai/docs/agents) / [skills](https://opencode.ai/docs/skills) / [commands](https://opencode.ai/docs/commands) / [plugins](https://opencode.ai/docs/plugins) | 见 §9.3 平台映射 |
| [AGENTS.md](https://agents.md/)（Linux Foundation AAIF） | 跨工具通用的常驻指令文件；Copilot 和 OpenCode 都会读取 |

### 2.3 由此得出的设计原则

| # | 原则 |
|---|---|
| P1 | **确定性优先**。凡是能写成脚本的步骤（检测、执行、解析、门禁、状态推进）都不交给 LLM |
| P2 | **验证独立**。作者 agent 无权判定通过；verifier 必须确定性，judge 必须只读 |
| P3 | **质量不等于覆盖率**。门禁看 mutation、catching、pass^k；coverage 只做参考 |
| P4 | **Agent 少，Skill 多**。专业化通过 skills 实现；新增服务类型 = 新增 registry 条目 + skills |
| P5 | **上下文隔离**。子 agent 只返回结构化摘要，细节写入文件；orchestrator 只持有 ID 和路径 |
| P6 | **状态持久**。JSON 计划只能通过 CLI 改状态；进度日志只追加；每个条目一次 commit；可断点续跑 |
| P7 | **循环有界**。每个条目最多重试 2 次；catching 失败只报告不修复；关键节点有人工闸门 |
| P8 | **双层护栏**。Hooks 负责快速拦截，但 Copilot hook 超时会 fail-open；CI 中的 `gta gate` 是最终裁决 |
| P9 | **Eval 驱动**。harness 本身的每次改动都要跑 evals |

### 2.4 v1 明确不做的事

以下内容在 v1 不做，避免过度设计：

- 不自建编排运行时（例如 LangGraph 服务）。Copilot / OpenCode 宿主本身就是运行时，`gta` 只负责确定性推进。
- 不自建 MCP server。toolkit 通过 shell 调用就够了；只有出现无 shell 的使用面时才考虑。
- 不做向量库 / RAG，不做独立 dashboard。`run.json` 直接接入现有 BI。
- Cucumber 不进默认路径。Record-replay 和性能测试放到 P5。

---

## 3. Harness 架构

### 3.1 分层

```
┌──────────────────────────────────────────────────────────────────────┐
│ Entry       /gta-bootstrap  /gta-pr  /gta-regression  /gta-incident   │  commands
├──────────────────────────────────────────────────────────────────────┤
│ Rules       AGENTS.md 片段 / Copilot rules：常驻护栏（短）               │  always-on
├──────────────────────────────────────────────────────────────────────┤
│ Agents      gta-orchestrator · gta-planner · gta-<type>-tester ×5 ·    │  roles
│             gta-reviewer                                               │
├──────────────────────────────────────────────────────────────────────┤
│ Skills      gta-* （渐进式加载：元数据 → 正文 → references/scripts）     │  knowledge
├──────────────────────────────────────────────────────────────────────┤
│ Toolkit     gta CLI：doctor/profile/impact/next/state/run/mutate/      │  deterministic
│             lint-tests/gate/report/env/hook                            │
├──────────────────────────────────────────────────────────────────────┤
│ Hooks       pre-tool 护栏 · stop 时 verify                              │  enforcement
├──────────────────────────────────────────────────────────────────────┤
│ State       .gta/*.json + progress.md + git commits + runs/<runId>/     │  durable
├──────────────────────────────────────────────────────────────────────┤
│ CI gate     gta gate（权威门禁，不依赖任何 agent）                        │  backstop
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 核心机制：`gta next` 负责推进，agent 负责执行

Orchestrator 的 prompt 保持很短，不在 prompt 里写复杂的 if-else。流程顺序由确定性状态机 `gta next` 决定，每次返回下一个节点：

```json
{ "node": "author", "itemId": "order.create#component-01",
  "specialist": "gta-api-tester",
  "skills": ["gta-testing-spring-components", "gta-stubbing-with-wiremock", "gta-designing-test-oracles"],
  "context": [".gta/test-plan.json", "src/main/java/com/acme/order/api/OrderController.java"] }
```

`node` 的取值为：`profile` | `plan` | `human-gate` | `author` | `verify` | `review` | `report` | `done`。Orchestrator 的工作循环是：调用 `gta next` → 执行该节点（agentic 节点交给子 agent，确定性节点直接执行 `gta` 命令）→ 用 `gta state` 回写结果 → 再次调用 `gta next`。

### 3.3 Agent 清单

| Agent | 来源 | 职责 | 用户可直接调用 | 模型档位 | 权限档案 |
|---|---|---|---|---|---|
| `gta-orchestrator` | 手写 | 入口；循环执行 `gta next`；派发子 agent；与人交互 | 是 | reasoning | `orchestrator` |
| `gta-planner` | 手写 | 读代码 → 生成 `flow-catalog.json` 和 `test-plan.json`（按风险排序）；PR 模式下推断变更意图和风险 | 否 | reasoning | `read-only` + 写 `.gta/` |
| `gta-api-tester` | 模板生成 | REST / gRPC / GraphQL 测试 | 是 | standard | `test-writer` |
| `gta-job-tester` | 模板生成 | `@Scheduled`、Quartz、ShedLock、Cloud Scheduler、Spring Batch 测试 | 是 | standard | `test-writer` |
| `gta-event-tester` | 模板生成 | Pub/Sub、Kafka、Spring Cloud Stream、应用事件、Workflow / 状态机测试 | 是 | standard | `test-writer` |
| `gta-pipeline-tester` | 模板生成 | Beam / Dataflow 测试 | 是 | standard | `test-writer` |
| `gta-system-tester` | 模板生成 | 部署态 system 测试（Cloud Run / GKE / 外部服务） | 是 | standard | `env-operator` |
| `gta-reviewer` | 手写 | 只读裁决：失败分类、oracle 审查、catching 判定（assessor）、对照计划 judge、把 mutation 缺口转成新条目 | 否 | reasoning | `read-only` |

- 5 个 specialist 由 `core/agents/gta-specialist.md.tmpl` 与 registry 中的 `specialists` 配置共同生成。它们的工作流完全相同，只在默认 skills、可写路径和权限档案上有差异。新增类型时不需要手写 prompt。
- 用户可以直接调用 specialist，例如"给这个 endpoint 写 Karate 测试"。这时它仍然遵守文件契约，并经过 `gta run` 验证。
- 并行策略：只有计划中存在跨 capability 的独立条目时才并行派发，并发上限为 `config.budgets.maxParallelSpecialists`（默认 3），以控制 token 成本。

### 3.4 Capability registry（动态派发）

文件位于 `core/registry/capabilities.yaml`，构建时编译为 JSON 随 toolkit 发布。

```yaml
schemaVersion: "1.0"
specialists:
  gta-api-tester:      { permissionProfile: test-writer,  writePaths: ["src/test/**"] }
  gta-job-tester:      { permissionProfile: test-writer,  writePaths: ["src/test/**"] }
  gta-event-tester:    { permissionProfile: test-writer,  writePaths: ["src/test/**"] }
  gta-pipeline-tester: { permissionProfile: test-writer,  writePaths: ["src/test/**"] }
  gta-system-tester:   { permissionProfile: env-operator, writePaths: ["src/test/**"] }

capabilities:
  - id: rest-api
    detect:
      buildDeps: [spring-boot-starter-web, spring-boot-starter-webflux]
      annotations: ["@RestController", "@RequestMapping"]
      files: ["**/openapi*.y*ml", "**/openapi*.json"]
    specialist: gta-api-tester
    layers: [unit, slice, component, contract]
    skills: [gta-testing-spring-web-slices, gta-testing-spring-components,
             gta-stubbing-with-wiremock, gta-testing-api-contracts]
  - id: scheduler
    detect:
      annotations: ["@Scheduled", "@EnableScheduling", "@SchedulerLock"]
      buildDeps: [spring-boot-starter-quartz, shedlock-spring]
    specialist: gta-job-tester
    layers: [unit, component]
    skills: [gta-testing-scheduled-jobs, gta-using-testcontainers]
  - id: spring-batch
    detect: { buildDeps: [spring-boot-starter-batch, spring-batch-core] }
    specialist: gta-job-tester
    layers: [unit, component]
    skills: [gta-testing-spring-batch-jobs, gta-using-testcontainers]
  - id: messaging
    detect:
      buildDeps: [spring-kafka, spring-cloud-gcp-starter-pubsub, spring-cloud-stream]
      annotations: ["@KafkaListener", "@EventListener", "@ApplicationModuleListener"]
    specialist: gta-event-tester
    layers: [unit, component, contract]
    skills: [gta-testing-event-flows, gta-using-testcontainers]
  - id: workflow
    detect:
      files: ["**/workflows/*.y*ml"]
      buildDeps: [spring-statemachine-core, temporal-sdk, camunda-bpm-spring-boot-starter]
    specialist: gta-event-tester
    layers: [unit, component, system]
    skills: [gta-testing-workflows]
  - id: beam-pipeline
    detect: { buildDeps: [beam-sdks-java-core, beam-runners-google-cloud-dataflow-java] }
    specialist: gta-pipeline-tester
    layers: [unit, component, system]
    skills: [gta-testing-beam-pipelines, gta-using-testcontainers]
  - id: deployed-runtime
    detect: { files: ["**/service.yaml", "**/helm/**/Chart.yaml", "**/k8s/**/*.yaml", "**/*.tf"] }
    specialist: gta-system-tester
    layers: [system]
    skills: [gta-testing-deployed-systems, gta-writing-karate-features]
  - id: gcp-managed-deps            # 不单独派发，只给命中的 specialist 追加 skill reference
    detect: { buildDeps: [google-cloud-spanner, google-cloud-firestore, google-cloud-bigquery,
                          google-cloud-storage, google-cloud-bigtable] }
    attachSkills: [gta-using-testcontainers]   # 使用其 references/gcp-emulators.md

always:
  skills: [gta-designing-test-oracles, gta-writing-junit5-tests, gta-managing-test-data]
```

检测（`gta profile`）完全由确定性代码完成：解析 Maven/Gradle 依赖树、扫描注解和部署清单。LLM 只在 `gta-planner` 中做补充判断，例如自研框架的入口识别。

### 3.5 状态与上下文

- **启动流程**：所有 agent 每个 session 都固定执行以下步骤。
  1. `gta doctor --quick`，检查 JDK、构建工具、Docker 和配置。
  2. 读取 `.gta/progress.md` 尾部和 `git log -n 20`。
  3. 调用 `gta next`。
  4. 动手写新东西之前，先执行 `gta run --smoke`，确认基线是绿的。
- **计划不可篡改**：agent 不能直接编辑 `.gta/test-plan.json`，只能通过 `gta state set <itemId> --status ... --evidence <runId>` 修改 `status`、`attempts`、`evidence` 三个字段。这一点由 hook 和 `gta validate` 双重保证。
- **子 agent 返回契约**：子 agent 返回的结构化摘要不超过约 1.5k token：
  `{ "itemId", "outcome": "written|blocked", "files": [...], "runId", "notes" }`
- **提交粒度**：每完成一个计划条目提交一次 commit，并带上 trailer，便于回滚和审计（§7.4）。

### 3.6 Hooks（确定性护栏）

| 用途 | Copilot（CLI / cloud agent） | OpenCode | 实现 |
|---|---|---|---|
| 工具调用前拦截 | `hooks.json` 中的 `preToolUse`，输出 `permissionDecision: "deny"` 加原因 | `.opencode/plugins/gta-guardrails.ts` 中的 `tool.execute.before`，抛错即可阻断 | `gta hook pre-tool` |
| 结束前验证 | `agentStop` / `subagentStop` | `session.idle` 事件 | `gta hook on-stop` → `gta run --changed --quick` |

两个平台的 hook 都只是很薄的一层壳，**规则只在 `gta hook` 中实现一份**。拦截规则如下：

- 写入 `src/main/**`（incident 修复模式经人工批准时除外）。
- 直接编辑 `.gta/*.json`（必须走 `gta state`）。
- 修改受管 harness 文件（以 `lock.json` 中的校验和判定）。
- 读取密钥文件。
- 对非 `gta-` 前缀的云资源执行 delete 操作。
- `git push --force`。

> 平台约束：VS Code 目前不是 Copilot hook 的支持面；Copilot hook 超时会 fail-open；cloud agent 会把 `ask` 当作 `deny`。所以 hooks 只是第一道防线，**CI 中的 `gta gate` 才是权威裁决**（P8）。

---

## 4. 运行模式与 Blueprint

节点类型：**D** = 确定性（`gta` 命令），**A** = agentic（子 agent），**H** = 人工。

### 4.1 `bootstrap`：首次接入一个服务

| # | 类型 | 节点 | 产出 |
|---|---|---|---|
| 1 | D | `gta doctor` | 环境报告 |
| 2 | D | `gta profile` | `service-profile.json` |
| 3 | A | `gta-planner`：映射 flow，按风险生成计划 | `flow-catalog.json`、`test-plan.json`（全部 `pending`） |
| 4 | D | `gta validate` | schema 校验 + registry 引用校验 |
| 5 | H | 审批计划（Copilot VS Code 用 handoff 按钮，其他使用面由 orchestrator 提问） | `approved` |
| 6 | A | specialist 写出一个条目的测试 | 测试文件 |
| 7 | D | `gta run --item`：编译、运行、解析，并做 "能失败" 检查（针对被测方法做定向 mutation，测试必须变红） | `runs/<runId>/results.json` |
| 8 | A | 失败时，specialist 根据结构化错误修复，最多 2 次，超出转 `needs-human` | — |
| 9 | D | `gta state set` + commit | 一个条目完成 |
| 10 | D | 对所有条目重复 6–9 后：`gta run --repeat 3`（新测试）+ `gta mutate --changed` + `gta lint-tests` | 质量数据 |
| 11 | A | `gta-reviewer`：oracle 审查、对照计划 judge；把缺口追加为新条目，最多追加 1 轮 | 评审结论 |
| 12 | D | `gta gate` + `gta report` | `run.json`、`report.md` |

### 4.2 `pr`：针对某次变更（hardening + catching）

1. **D** `gta impact`：根据 git diff 找出受影响的类，再映射到受影响的 flows。
2. **A** `gta-planner`：推断本次变更的意图，列出可能出错的方式，并为每种风险生成两类条目：
   - `intent=hardening`：在新代码上应该通过，作为回归测试保留。
   - `intent=catching`：针对风险设计，在新代码上失败即说明可能存在缺陷。
3. 之后同 bootstrap 的 6–12，但有两点区别：
   - **catching 测试失败时不会被"修绿"**，而是交给 `gta-reviewer` 作为 assessor 判定是否为真实缺陷。只有判定为 `product-defect` 的结果才写进报告和 PR 评论；其他结果丢弃并记录（human-on-the-loop）。
   - PIT 只对 diff 跑。

### 4.3 `regression`：夜间或发布前

`gta doctor` → `gta env up`（按需创建临时环境）→ `gta run --suite all`（包括 system 层）→ **A** `gta-reviewer` 对失败做分类 → `gta env down` → `gta report`。被判定为 `product-defect` 的失败会生成 issue 草稿。

### 4.4 `incident`：线上缺陷

**A** `gta-planner` 根据缺陷描述生成 `intent=reproducing` 条目 → **A** specialist 写测试 → **D** 验证该测试在当前代码上失败，并且失败原因正确 → **A** `gta-reviewer` 确认 → 交给开发修复。修复后该测试转为 hardening，形成 bug → test 闭环。

### 4.5 失败分类（reviewer 输出的枚举）

`product-defect` | `test-defect` | `environment` | `flaky` | `expected-change`

---

## 5. 各类服务的测试方法

> 测试层级命名统一为：`unit` → `slice` → `component`（单服务 + 真实依赖容器）→ `contract` → `system`（部署态黑盒）。
> 通用原则：先找到 flow 的入口和副作用，再决定在哪一层、用什么工具、用什么 oracle 验证。

### 5.1 API（Spring MVC / WebFlux / gRPC）

| 层 | 工具 | 重点 |
|---|---|---|
| unit | JUnit 5 + AssertJ + Mockito；jqwik（property-based） | 业务规则、边界值、金额与时间计算 |
| slice | `@WebMvcTest` + `MockMvcTester`（Boot 3.4+）/ `@WebFluxTest`；`@JsonTest` | 参数校验、ProblemDetail 错误映射、安全、序列化 |
| component | `@SpringBootTest(RANDOM_PORT)` + Testcontainers（`@ServiceConnection`）+ WireMock + REST Assured | 真实 DB 与消息；下游超时、5xx、重试、熔断；事务 |
| contract | Pact（多语言消费者）或 Spring Cloud Contract（纯 Spring）；oasdiff 检测 breaking change；Schemathesis 做 conformance、fuzz 和 stateful 测试 | 每个 operation × status code 都覆盖 |
| system | Karate（目标为 Cloud Run tagged revision 或 GKE PR namespace） | 业务链路、鉴权（ID token）、幂等、并发 |

- system 层默认用 **Karate** 的理由：
  - 一个 feature 文件自包含，生成和 review 成本都低；
  - 内置 fuzzy match（`#uuid`、`#notnull`），适合做忽略动态字段的回归比对；
  - 内置 mock、并行执行和数据驱动，还能复用为 Gatling 性能测试；
  - 与语言无关，可以测非 Java、非 GCP 的服务。
- 版本注意：当前为 Karate 2.x，JUnit 集成模块是 `karate-junit6`；从 2.1 起 mock server 默认关闭 Java interop。
- **Cucumber** 只在业务方必须维护 Gherkin 规格、并且愿意承担 step definition 维护成本时启用。由 config 开关控制，默认关闭。

### 5.2 Scheduler（`@Scheduled` / Quartz / ShedLock / Cloud Scheduler）

**原则：触发与逻辑分开测，测试里永远不等 cron。**

| 测试点 | 方法 |
|---|---|
| 业务逻辑 | 直接调用 job bean 或底层 service |
| Cron 正确性 | 用 `CronExpression.parse(expr).next(t)` 做表驱动测试，覆盖时区、DST 切换、月末、闰年 |
| 调度接线 | 用 `@MockitoSpyBean` + Awaitility 验证 job 被触发；其他测试通过 property 关闭调度（`@EnableScheduling` 放在条件配置类上） |
| 时间 | 注入 `java.time.Clock`，测试里用固定时钟或可推进时钟 |
| 分布式锁 | ShedLock + Testcontainers DB：并发触发两次，断言只执行一次；验证 `lockAtMostFor` |
| 幂等与重跑 | 连续执行两次，终态一致；中途失败后重跑，无重复、无遗漏 |
| Quartz | JDBC JobStore + 容器化 DB；misfire 策略；集群模式 |
| Cloud Scheduler → Cloud Run | 端点按 API 方式测试（包括 OIDC 校验）；system 层用 `gcloud scheduler jobs run` 强制触发，再轮询副作用 |
| 可观测性 | 用 `SimpleMeterRegistry` 断言成功、失败计数和耗时被记录 |

### 5.3 Spring Batch

| 测试点 | 方法 |
|---|---|
| Reader / Processor / Writer | 分别单测；step-scoped bean 用 `StepScopeTestUtils` + `MetaDataInstanceFactory` |
| Step | Batch 6 用 `JobOperatorTestUtils.startStep(...)`（`JobLauncherTestUtils` 已废弃）；Batch 5 用 `launchStep(...)` |
| Job | `@SpringBatchTest` + Testcontainers + 输入 fixtures → 断言 `ExitStatus`、read/write/skip/filter 计数，输出与 golden 文件比对 |
| 重启 | 在第 N 条注入失败 → 状态为 FAILED → restart → 状态为 COMPLETED，且没有重复写入 |
| Skip / Retry | 构造 poison records，断言被跳过的数量和去向 |
| 对账 | `read = written + skipped + filtered`；关键字段 checksum |
| 隔离 | 用 `JobRepositoryTestUtils` 清理元数据；Batch 6 起不再支持 JUnit 4 |

### 5.4 Dataflow / Beam

| 测试点 | 方法 |
|---|---|
| 业务逻辑 | 从 `DoFn` 中抽离成普通类，用纯 JUnit 测试 |
| Transform | `TestPipeline` + `PAssert`（DirectRunner），保持 abandoned-node 检查开启 |
| Streaming | `TestStream` 覆盖 watermark、迟到数据、窗口、trigger |
| Coder | `CoderProperties` |
| IO | Pub/Sub 和 BigQuery emulator（BigQuery 用 goccy 镜像，功能有限）、fake-gcs-server；emulator 不支持的部分放到临时 dataset / bucket 中测 |
| system | 每条 pipeline 只做 1–2 个真实 Dataflow 运行（Flex Template），资源名带 runId，运行后清理 |
| 数据质量 | 行数、schema、去重、DLQ 内容；与独立计算结果做 differential 比对 |

### 5.5 事件驱动（Pub/Sub / Kafka / 应用事件）

- broker 用容器运行；异步断言只用 Awaitility，**禁止 `Thread.sleep`**。
- 必测场景：重复投递（幂等）、乱序、毒消息进 DLQ、重试退避、消费者重启后的 ack/offset 行为。
- Schema 演进做兼容性检查（Avro / Protobuf）；AsyncAPI 用 Microcks 做 conformance。
- Outbox：事务提交后才发消息，回滚时不发。模块间事件用 Spring Modulith `Scenario`。

### 5.6 Workflow / 编排

| 类型 | 方法 |
|---|---|
| GCP Workflows | 下游端点用 WireMock 替身；部署到临时环境后通过 Executions API 执行，断言执行结果和步骤历史 |
| Spring State Machine | `StateMachineTestPlan` 覆盖所有状态迁移 |
| Temporal / Camunda | 使用官方 test environment（支持时间跳跃和 activity mock） |
| 通用方法 | 把 flow 抽象为状态机，生成覆盖所有边、每个错误分支和补偿路径的测试用例 |

### 5.7 部署态（Cloud Run / GKE / 非 GCP）

- **临时环境**：
  - Cloud Run：`gcloud run deploy --no-traffic --tag gta-<shortRunId>`，得到独立 URL，不影响线上流量。
  - GKE：每次运行创建 namespace `gta-<shortRunId>`。
  - 配置断言用 `terraform test`。
- **跨服务场景**：Karate 驱动入口，轮询等待最终一致性；断言可以落在 API、DB、BigQuery 或 trace 上。
- **非 GCP 外部服务**：低层测试用 WireMock stub（从 sandbox 录制，定期刷新并检测漂移）+ 契约测试；E2E 只在 vendor sandbox 中做少量冒烟。
- **测试数据**：资源名带 runId 前缀，运行后 teardown，再加 `gta env gc` 定期兜底清理；禁止使用生产 PII。

### 5.8 选择矩阵（`gta-planner` 使用）

| Flow 特征 | 层 | 工具 | Oracle |
|---|---|---|---|
| 同步 HTTP、单服务 | slice + component | MockMvcTester / REST Assured + Testcontainers + WireMock | 响应 + DB 状态 |
| 对外暴露或有多个消费者 | contract + system | Pact/SCC + Schemathesis + Karate | 契约 + golden 响应 |
| 定时触发 | unit + component | 直接调用 + Clock + Awaitility + ShedLock | 副作用 + 幂等 |
| 批处理 | component | `@SpringBatchTest` + Testcontainers | 对账 + golden 输出 + 重启 |
| 数据管道 | unit + component | TestPipeline + PAssert + TestStream | 期望数据 / differential |
| 异步消息 | component | 容器化 broker + Awaitility | 终态 + DLQ + 重复投递 |
| 编排 | component + system | 状态机路径测试 + 临时环境执行 | 执行历史 + 终态 |
| 跨服务业务链路 | system | Karate + 临时环境 | 业务终态 |

---

## 6. Skills 目录

命名遵循 §7.2：前缀 `gta-` 加动名词短语。每个 skill 的正文写"默认路径 + 例外出口"，深入资料放在 `references/`（只深一层）。

| Skill | 使用者 | 内容（references/ 中的文件） |
|---|---|---|
| `gta-profiling-services` | planner | 如何解读 `gta profile` 的输出、识别自研入口 |
| `gta-mapping-flows` | planner | 从入口追踪到副作用；风险评级规则 |
| `gta-planning-tests` | planner | §5.8 选择矩阵、层级取舍、计划条目写法 |
| `gta-analyzing-change-impact` | planner | diff → 意图 → 风险 → hardening/catching 条目 |
| `gta-designing-test-oracles` | 所有 specialist、reviewer | 状态断言、golden/approval（`golden-files.md`）、differential、metamorphic、invariant、property 型 oracle |
| `gta-writing-junit5-tests` | 所有 specialist | §7.3 的测试代码规范、AssertJ、参数化、Surefire 与 Failsafe 的划分 |
| `gta-managing-test-data` | 所有 specialist | Instancio / builders、fixtures、唯一前缀、清理、PII |
| `gta-testing-spring-web-slices` | api | MockMvcTester、WebFlux、JsonTest、安全测试 |
| `gta-testing-spring-components` | api | SpringBootTest + REST Assured（`grpc.md`） |
| `gta-stubbing-with-wiremock` | api、system | stub、故障注入、录制、漂移检测（`external-services.md`） |
| `gta-testing-api-contracts` | api | Pact（`pact.md`）、SCC（`spring-cloud-contract.md`）、`oasdiff.md`、`schemathesis.md` |
| `gta-using-testcontainers` | 多个 | `@ServiceConnection`、容器复用（`gcp-emulators.md`、`kafka.md`、`databases.md`） |
| `gta-testing-scheduled-jobs` | job | §5.2 |
| `gta-testing-spring-batch-jobs` | job | §5.3（`batch5-vs-batch6.md`） |
| `gta-testing-beam-pipelines` | pipeline | §5.4（`test-stream.md`、`dataflow-runs.md`） |
| `gta-testing-event-flows` | event | §5.5（`awaitility.md`、`modulith-scenario.md`） |
| `gta-testing-workflows` | event | §5.6（`gcp-workflows.md`、`state-machines.md`） |
| `gta-writing-karate-features` | system | 项目结构、match、数据驱动、鉴权、并行、mock（`karate-v2-notes.md`） |
| `gta-testing-deployed-systems` | system | §5.7（`cloud-run.md`、`gke.md`、`cost-control.md`） |
| `gta-reviewing-test-quality` | reviewer | oracle 审查清单、反模式、catching 判定规则、失败分类 |
| `gta-running-mutation-tests` | reviewer、planner | 解读 PIT 结果、存活 mutant 转新条目、判定等价 mutant |
| `gta-writing-cucumber-features` | api、system（可选，默认关闭） | 仅当 `config.features.cucumber=true` 时加载 |

共 22 个，覆盖流程、规范、技术、质量四类。报告和 CI 接入都是确定性工作，由 toolkit 和 `docs/` 承担，不做成 skill。

---

## 7. 命名与工程规范（统一标准）

### 7.1 大小写与格式总则

| 对象 | 规范 | 示例 |
|---|---|---|
| 目录名、Markdown 文件名、skill / agent / command 名 | kebab-case | `gta-testing-spring-batch-jobs` |
| Python 模块（toolkit 与 skill scripts） | snake_case（PEP 8） | `parse_surefire.py` |
| JSON 字段 | camelCase | `schemaVersion`、`flowId` |
| YAML（只用于 `core/` 中的手写配置） | camelCase 字段，与 JSON 一致 | `permissionProfile` |
| 枚举值 | kebab-case | `product-defect`、`needs-human` |
| CLI 子命令和参数 | kebab-case | `gta lint-tests --changed` |
| 路径分隔符 | 一律使用 `/` | — |

### 7.2 Harness 资产命名

| 资产 | 规则 | 正则 / 约束 | 示例 |
|---|---|---|---|
| Agent | `gta-<role>`，role 用名词 | `^gta-[a-z]+(-[a-z]+)*$` | `gta-orchestrator`、`gta-api-tester` |
| Specialist | `gta-<type>-tester` | 同上 | `gta-pipeline-tester` |
| Skill | `gta-<gerund>-<object>` | `^gta-[a-z0-9]+(-[a-z0-9]+)*$`，≤64 字符，与所在目录同名 | `gta-writing-karate-features` |
| Command | `gta-<mode>` | 模式枚举 | `gta-bootstrap`、`gta-pr`、`gta-regression`、`gta-incident` |
| Hook / plugin | `gta-guardrails` | — | `gta-guardrails.ts` |
| Rules 文件 | `gta-<topic>.md` | — | `gta-guardrails.md` |
| Capability id | 名词 kebab | — | `rest-api`、`spring-batch` |
| 权限档案 | 名词 kebab | `orchestrator`、`read-only`、`test-writer`、`env-operator` | — |
| Schema 文件 | `<artifact>.v<major>.schema.json`，`$id` 为 `urn:gta:schema:<artifact>:v<major>` | — | `test-plan.v1.schema.json` |
| 模板文件 | `<target-name>.tmpl` | — | `gta-specialist.md.tmpl` |
| ADR | `docs/adr/NNNN-<slug>.md` | 4 位序号 | `0001-blueprint-over-free-agent.md` |

**Frontmatter 标准（core 源文件）**
- Skill：只包含 `name` 和 `description`，以保证跨平台可移植。`description` 用第三人称，格式为"做什么 + Use when …"，并包含触发词，长度 ≤1024。
- Agent（中立格式）：`name`、`description`、`permissionProfile`、`modelTier`（`reasoning` | `standard`）、`userInvocable`、`subagents`。平台字段由 build 映射生成（§9.3），**core 中不直接写平台字段**。

**正文结构标准**
- Skill：`# 标题` → `## Quick start` → `## Workflow`（清单式步骤）→ `## Rules`（MUST / MUST NOT）→ `## References`。正文 ≤500 行，目标 ≤300 行。
- Agent：`## Role` → `## Inputs` → `## Startup routine` → `## Workflow` → `## Output contract` → `## Rules`。正文 ≤30,000 字符（Copilot 的上限）。

### 7.3 被测仓库中的测试代码规范

| 对象 | 规则 |
|---|---|
| unit / slice 测试类 | `<Subject>Test`，由 Surefire 执行 |
| component / contract 测试类 | `<Subject>IT`，由 Failsafe 执行 |
| 包结构 | 与被测类同包；共享支撑代码放在 `<basePackage>.support` |
| 测试方法 | `should<Outcome>When<Condition>()`，配合 `@DisplayName("[<flowId>] …")` |
| JUnit tag | 层级 tag 必须是 `unit`、`slice`、`component`、`contract`、`system` 之一；另加 `flow:<flowId>`；gta 生成的测试额外加 `gta` |
| Karate | 文件路径 `src/test/resources/karate/<domain>/<flow-name>.feature`；tag 为 `@system @flow=<flowId>`；runner 为 `<basePackage>.system.KarateSystemIT` |
| Fixtures | `src/test/resources/fixtures/<flowId>/<case>.json` |
| Golden | `src/test/resources/golden/<flowId>/<case>.approved.json`（ApprovalTests 约定） |

### 7.4 ID、Git 与云资源

| 对象 | 格式 | 示例 |
|---|---|---|
| flowId | `<domain>.<flow-name>`，正则 `^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$` | `order.create`、`order.reconcile-nightly` |
| planItemId | `<flowId>#<layer>-<nn>` | `order.create#component-01` |
| runId | `r<yyyymmddhhmmss>-<sha7>`（小写，满足 DNS 命名要求） | `r20261103120000-a1b2c3d` |
| shortRunId | runId 的末 14 位 | `120000-a1b2c3d` |
| 分支 | `gta/<mode>/<slug>` | `gta/pr/order-discount` |
| Commit | Conventional Commits：`test(<domain>): <summary>`，带 trailer `Gta-Plan-Item: <planItemId>` 和 `Gta-Run-Id: <runId>` | `test(order): cover gateway timeout on create` |
| 云资源名 | `gta-<shortRunId>-<purpose>`，≤63 字符 | `gta-120000-a1b2c3d-orders` |
| 云资源 label | `gta-run-id=<runId>`、`gta-ttl-hours=<n>` | — |

---

## 8. 文件契约与 Schema

### 8.1 被测仓库中的 `.gta/`

```
.gta/
├── config.json              # 仓库级配置                          （提交）
├── lock.json                # 已安装的 harness 版本/平台/受管文件校验和（提交，由 install 写入）
├── toolkit/                 # gta CLI（vendored，受管）            （提交）
├── service-profile.json     # gta profile 的产出                   （提交）
├── flow-catalog.json        # gta-planner 的产出                   （提交）
├── test-plan.json           # 当前分支的计划；只能通过 gta state 修改  （提交在工作分支）
├── progress.md              # 追加式进度日志                       （提交在工作分支）
└── runs/<runId>/            # 单次运行产物                          （gitignore；上传为 CI artifact）
    ├── run.json             # 模式、平台、节点耗时、重试次数、gate 结果
    ├── results.json         # 每个测试的结果（统一自 Surefire/Failsafe/Karate/PIT）
    └── report.md
```

所有 JSON 产物都有统一信封：
```json
{ "$schema": "urn:gta:schema:test-plan:v1", "schemaVersion": "1.0",
  "generatedBy": { "tool": "gta", "version": "1.0.0", "agent": "gta-planner" },
  "generatedAt": "2026-11-03T12:00:00Z", "...": "..." }
```

### 8.2 计划条目示例（`test-plan.json` → `items[]`）

```json
{
  "id": "order.create#component-01",
  "flowId": "order.create",
  "capability": "rest-api",
  "layer": "component",
  "intent": "hardening",
  "specialist": "gta-api-tester",
  "skills": ["gta-testing-spring-components", "gta-stubbing-with-wiremock"],
  "scenario": "支付网关超时时返回 503，订单状态为 PENDING_PAYMENT，且不发送 order-created 消息",
  "oracle": { "type": "state", "assertions": ["status=503 ProblemDetail", "orders.status=PENDING_PAYMENT", "no message on order-created"] },
  "target": { "testClass": "com.acme.order.api.OrderCreateIT" },
  "priority": "p1",
  "status": "pending",
  "attempts": 0,
  "evidence": null
}
```

枚举定义：
- `intent`：`hardening` | `catching` | `reproducing`
- `status`：`pending` | `in-progress` | `passed` | `failed` | `blocked` | `needs-human`
- `priority`：`p0`–`p3`

### 8.3 `config.json` 示例

```json
{
  "$schema": "urn:gta:schema:config:v1", "schemaVersion": "1.0",
  "build": { "tool": "maven", "modules": ["order-service"] },
  "features": { "cucumber": false, "systemLayer": false },
  "environments": { "gcpProject": null, "region": "asia-east1", "allowCloud": false },
  "budgets": { "maxAttemptsPerItem": 2, "maxReviewRounds": 1, "maxParallelSpecialists": 3, "maxCloudCostUsd": 20 },
  "humanGates": { "planApproval": true, "productionCodeChange": true },
  "qualityGates": { "newTestsPassRepeat": 3, "changedCodeMutationScoreMin": 0.7, "requireOracleReview": true }
}
```

### 8.4 Toolkit CLI（少量高价值命令）

| 命令 | 作用 |
|---|---|
| `gta doctor [--quick]` | 环境与配置检查；检测受管文件漂移和 schema 版本是否匹配 |
| `gta profile` | 确定性检测 → `service-profile.json` |
| `gta impact --base <ref>` | diff → 受影响的 flows |
| `gta validate` | 校验 schema、registry 引用，以及计划是否被非法改动 |
| `gta next` | 返回下一个 blueprint 节点（§3.2） |
| `gta state set <itemId> ...` | 唯一允许修改计划状态的入口 |
| `gta run [--item | --changed | --suite] [--repeat k] [--smoke]` | 构建、执行、解析，并做 "能失败" 检查 |
| `gta mutate --changed` | PIT 增量 mutation |
| `gta lint-tests` | 反模式检测：`Thread.sleep`、`@Disabled`、无断言、仅 `notNull`、过度 mock |
| `gta gate` | 质量门禁（CI 权威裁决），退出码 0 表示通过，1 表示不通过 |
| `gta report` | 生成 `run.json` 和 `report.md`，可选输出 PR 评论正文 |
| `gta env up | down | gc` | 临时环境的创建、销毁和兜底清理 |
| `gta hook pre-tool | on-stop` | 供平台 hook 调用 |

所有命令默认输出简洁 JSON（`--format concise|detailed`），错误信息包含可执行的修复建议。实现语言为 Python ≥3.10，唯一的运行时依赖是锁定版本的 `jsonschema`。

---

## 9. 仓库结构、构建与分发

### 9.1 本仓库结构

```
generic-testing-agent/
├── README.md
├── plan.md
├── AGENTS.md                          # 在本仓库中开发 harness 的规则（给人和 agent 看）
├── CHANGELOG.md
├── VERSION                            # SemVer
├── core/                              # ★ 单一事实源（手写）
│   ├── agents/
│   │   ├── gta-orchestrator.md
│   │   ├── gta-planner.md
│   │   ├── gta-reviewer.md
│   │   └── gta-specialist.md.tmpl
│   ├── commands/
│   │   ├── gta-bootstrap.md
│   │   ├── gta-pr.md
│   │   ├── gta-regression.md
│   │   └── gta-incident.md
│   ├── rules/
│   │   └── gta-guardrails.md
│   ├── skills/
│   │   └── gta-<gerund>-<object>/
│   │       ├── SKILL.md
│   │       ├── references/<topic>.md
│   │       ├── scripts/<verb>_<object>.py
│   │       └── assets/<name>.<ext>.tmpl
│   ├── registry/
│   │   └── capabilities.yaml
│   ├── schemas/
│   │   └── <artifact>.v1.schema.json
│   └── toolkit/
│       ├── gta.py                     # 入口
│       ├── gta/                       # 包：commands/ parsers/ hooks/ state/
│       └── tests/
├── platforms/
│   ├── copilot/
│   │   ├── mapping.yaml               # 中立字段 → Copilot frontmatter
│   │   ├── plugin.json.tmpl
│   │   └── hooks.json.tmpl
│   └── opencode/
│       ├── mapping.yaml               # 中立字段 → OpenCode frontmatter
│       ├── opencode.json.tmpl
│       └── gta-guardrails.ts.tmpl
├── dist/                              # ★ 生成物（提交；CI 校验与 core 同步）
│   ├── copilot/
│   └── opencode/
├── tools/
│   ├── build.py                       # core + platforms → dist
│   ├── lint.py                        # §9.4 的检查
│   └── install.py                     # dist → 目标仓库，写 .gta/lock.json
├── evals/
│   ├── fixtures/<fixture-id>/         # 示例服务 + 植入缺陷
│   ├── tasks/<task-id>.task.json
│   ├── graders/
│   └── run_evals.py
├── docs/
│   ├── adr/NNNN-<slug>.md
│   ├── adding-a-capability.md
│   └── ci-integration.md
└── .github/workflows/ci.yml           # lint → toolkit 测试 → build --check → evals 冒烟
```

### 9.2 发行布局

**`dist/copilot/`：Agent Plugins 1.0**（适用于 VS Code、Copilot CLI、Copilot app，以及通过仓库设置启用的 cloud agent）
```
dist/copilot/
├── plugin.json                         # 含 Agent Plugins $schema
├── skills/gta-*/SKILL.md ...           # 跨客户端共享
└── com.github.copilot/
    ├── agents/gta-*.agent.md
    ├── commands/gta-*.md
    ├── rules/gta-guardrails.md
    └── hooks/hooks.json                # preToolUse / agentStop → gta hook
```

**`dist/opencode/`**
```
dist/opencode/
├── opencode.json                       # 默认 agent、权限基线
├── AGENTS.md.snippet                   # 合并进目标仓库 AGENTS.md 的护栏段落
└── .opencode/
    ├── agents/gta-*.md
    ├── commands/gta-*.md
    ├── skills/gta-*/SKILL.md ...
    └── plugins/gta-guardrails.ts
```

**安装方式**

```
python3 tools/install.py --platform copilot  --mode plugin --target ../order-service
python3 tools/install.py --platform copilot  --mode embed  --target ../order-service
python3 tools/install.py --platform opencode               --target ../order-service
```

三条命令的含义：
- `--mode plugin`：只安装 toolkit，并在 `.github/copilot/settings.json` 的 `enabledPlugins` 中启用插件。
- `--mode embed`：映射到 `.github/agents|skills|hooks|prompts`，用于插件被企业策略禁用的环境。
- OpenCode：复制 `.opencode/` 目录，合并 `AGENTS.md`。

无论哪种方式，toolkit 都会 vendored 到 `.gta/toolkit/`，这样 CI 门禁不依赖任何 agent。企业内部可以搭建 Copilot 插件 marketplace（`marketplace.json`），插件标识为 `gta@<marketplace>`，并通过 enterprise managed settings 统一要求或屏蔽插件。

### 9.3 平台映射（`platforms/*/mapping.yaml`）

| 中立字段 | Copilot（`.agent.md`） | OpenCode（`.md`） |
|---|---|---|
| `description` | `description` | `description` |
| `userInvocable: false` | `user-invocable: false` | `mode: subagent` + `hidden: true` |
| `userInvocable: true`（specialist） | `user-invocable: true` | `mode: all` |
| orchestrator | 默认值 | `mode: primary` |
| `subagents: [...]` | `tools` 含 `agent`；VS Code 中用 `agents: [...]` 白名单 | `permission.task`：先 `"*": deny`，再逐个 allow |
| `permissionProfile: read-only` | `tools: [read, search]` | `permission`：`edit: deny`，`bash` 只允许 `gta *` |
| `permissionProfile: test-writer` | `tools: [read, search, edit, execute]` | `edit: allow`，`bash`：`gta *` 和构建命令 allow，其余 ask |
| `permissionProfile: env-operator` | 同 test-writer；云命令由 hook 限定只能操作 `gta-` 资源 | 同 test-writer，`gcloud *` 和 `kubectl *` 设为 ask |
| `modelTier` | `model`（部分使用面会忽略） | `model: provider/model-id` |
| 人工闸门 | VS Code 用 `handoffs`；cloud agent 会忽略 handoffs，改为 orchestrator 停下提问 | orchestrator 提问 |

> 平台字段变化频繁（例如 `infer` 已被 `disable-model-invocation` / `user-invocable` 取代，OpenCode 的 `tools` 已被 `permission` 取代）。所有平台差异只存在于 mapping 和模板中，升级时只需改这一处。

### 9.4 Lint 与构建检查（`tools/lint.py`，CI 必过）

1. 名称符合 §7.2 的正则；skill 名与目录名一致；名称全局唯一。
2. `description` 非空，≤1024 字符，用第三人称（不出现 "I can" / "You can"），包含 "Use when"。
3. `SKILL.md` ≤500 行；引用只深一层；超过 100 行的引用文件有目录；路径中没有反斜杠。
4. Agent 正文 ≤30,000 字符，并包含 §7.2 规定的章节。
5. Registry 中引用的 skill 和 specialist 都存在；所有 schema 自身合法。
6. `build.py --check`：`dist/` 与 `core/` 完全同步。
7. Toolkit 单元测试全部通过。

### 9.5 版本与升级

- 项目整体采用 SemVer：artifact schema 升级 major 版本、或 agent 输出契约出现破坏性变更时，升级 MAJOR。
- `install.py` 写入 `.gta/lock.json`，内容包括版本、平台、模式和每个受管文件的 sha256。
- `gta doctor` 检测本地是否修改了受管文件（漂移），以及 schema 版本是否兼容。
- 升级方式是用新版本重新执行 `install.py`。被本地修改过的受管文件不会被覆盖，而是报告冲突。

---

## 10. 安全与治理

| 主题 | 措施 |
|---|---|
| 最小权限 | 按 §9.3 的权限档案授权；reviewer 和 planner 只读；只有 `gta-system-tester` 能接触云 |
| 双层护栏 | Hooks 负责快速拦截（§3.6），CI 中的 `gta gate` 做权威裁决，再加分支保护 |
| 生产代码 | 默认禁止修改；incident 修复必须经人工批准（`humanGates.productionCodeChange`） |
| 测试完整性 | 禁止削弱断言、删除测试、加 `@Disabled`、把期望值改成实际值；`expected-change` 必须由 reviewer 判定并写进报告 |
| 云成本 | `allowCloud` 默认为 false；有预算上限；资源带 TTL label；`gta env gc` 定期兜底清理 |
| 密钥与数据 | 凭证只从环境注入；hook 拦截读取密钥文件；禁止使用生产 PII |
| Prompt 注入 | 仓库内容、测试输出和外部响应一律当作数据，不当作指令（写入 `gta-guardrails.md`） |
| 审计 | 每个条目一次 commit 并带 trailer；`run.json` 记录节点、重试次数和结果 |
| 归属 | 用 CODEOWNERS 按目录划分（toolkit、skills/各领域、platforms）；重大决策写 ADR |

---

## 11. Evals 与可观测性

### 11.1 Harness 自身的 evals（遵循 Anthropic 的定义）

- **Task**（`evals/tasks/<task-id>.task.json`）：每个 task 包含 fixture、模式、输入、植入的缺陷清单、grader 配置和参考解。
- **Fixtures**：3 个代表性服务（API、Scheduler + Batch、Beam），每个植入 10–20 个已知缺陷，例如边界错误、缺少幂等、时区错误、重启后重复写、窗口错误、契约字段改名。
- **Grader**：
  - **code**（主要）：已知缺陷被测试抓到（fail-to-pass）、`gta gate` 的结果、没有违反护栏。
  - **model**：用评分 rubric 评价 oracle 质量和可读性。
  - **human**：定期抽检。
- **指标**：
  - 缺陷检出率；
  - 误报率（正确的代码上报失败）；
  - 新测试的 pass^3；
  - 每个条目的 token 和耗时成本。
  - 每个 task 跑 k=3 次 trial，同时报告 pass@3 和 pass^3。
- **两类 eval**：
  - capability eval：初始通过率低，用来推动改进。
  - regression eval：通过率应接近 100%，每次修改 skill 或 agent 后都必须跑。
- **Skill 触发 eval**：每个 skill 至少准备 3 个"应该触发"和 3 个"不应该触发"的输入。
- **隔离**：每次 trial 都使用全新克隆的 fixture，并定期阅读 transcript。
- **起步**：先从团队真实事故中提炼 20–50 个 task。

### 11.2 运行期可观测性

`run.json` 记录以下内容：节点序列和耗时、重试次数、平台与使用面、模型（如果能获取）、token 消耗（如果宿主提供）、gate 结果。汇总到现有 BI 的指标有：新增的 verified 测试数、确认的 catching 数、变更代码 mutation score 的变化、flaky 率、人工接受率、每个条目的成本。

---

## 12. 路线图

| 阶段 | 范围 | 退出标准 |
|---|---|---|
| **P0 骨架** | Schemas；registry；toolkit（`doctor`、`profile`、`validate`、`next`、`state`、`run`、`report`、`hook`）；`build`、`lint`、`install`；orchestrator、planner、reviewer 和 specialist 模板；API fixture 与 10 个 eval task | 两个平台都能对 API fixture 跑通 bootstrap，并产出合法的 `test-plan.json` 和至少 1 个 verified 测试 |
| **P1 API** | `gta-api-tester` 及其 skills（slice、component、WireMock、contract、Karate） | 在 1 个真实 API 服务上试点；植入缺陷的检出率达到约定阈值 |
| **P2 Job + 质量** | `gta-job-tester`；`gta mutate`、`gta lint-tests`、`gta gate`；完善 reviewer | mutation 补测循环跑通；CI 门禁上线 |
| **P3 数据与事件** | `gta-pipeline-tester`、`gta-event-tester` | Beam 服务和消息服务各试点 1 个 |
| **P4 PR + 部署态** | `gta impact`、catching 流程；`gta-system-tester`、`gta env`；incident 模式 | PR 中自动补测并上报确认的 catch；夜间 regression 报告 |
| **P5 进阶** | record-replay / shadow diff 回归基线；Karate-Gatling 性能测试；内部 marketplace 发布 | 按需推进 |

---

## 13. 待确认问题

1. **版本**：构建工具用 Maven 还是 Gradle？Java、Spring Boot、Spring Batch、Beam 分别是什么版本？这会影响 Batch 5 与 6 的 API 选择，以及 `MockMvcTester` 是否可用。
2. **Copilot 使用面**：主要在 VS Code、Copilot CLI 还是 cloud agent 上用？VS Code 不支持 hook，cloud agent 会忽略 handoffs。企业策略是否允许启用插件，决定了用 `plugin` 还是 `embed` 安装。
3. **Python**：开发机和 CI 上能否保证有 Python ≥3.10？如果不能，toolkit 需要改为 JBang 或 Java 实现。
4. **云权限**：是否有测试专用的 GCP project？agent 和 CI 能否创建 Cloud Run tagged revision、GKE namespace 和临时 dataset？
5. **测试归属**：system 层的 Karate 套件放在各服务仓库，还是集中放在一个测试仓库？
6. **CI**：用 GitHub Actions、Cloud Build 还是 Jenkins？这决定 `docs/ci-integration.md` 先写哪一种。
7. **契约现状**：是否已有 OpenAPI 文档或 Pact broker？消费者是否涉及多种语言？
8. **模型**：企业内可用的模型有哪些？用于 `reasoning` 和 `standard` 两档的映射。
9. **命名空间**：是否沿用 `gta` 前缀？是否需要加组织前缀，例如 `acme-gta-*`？

---

## 附录 A：参考资料

| 类别 | 链接 |
|---|---|
| 业界案例 | [Meta ACH](https://arxiv.org/abs/2501.12862) · [Meta JiTTests](https://engineering.fb.com/2026/02/11/developer-tools/the-death-of-traditional-testing-agentic-development-jit-testing-revival/) · [JiTTests 规模化](https://engineering.fb.com/2026/10/29/security/catching-bugs-before-they-land-jittestings-human-on-the-loop-approach-at-full-scale/) · [Uber AutoCover](https://homes.cs.washington.edu/~rjust/publ/auto_cover_icse_2026.pdf) · [Spotify Honk 反馈回路](https://engineering.atspotify.com/2025/12/feedback-loops-background-coding-agents-part-3) · [Stripe Minions（ByteByteGo 解读）](https://blog.bytebytego.com/p/how-stripes-minions-ship-1300-prs) · [Airbnb 测试迁移](https://www.infoq.com/news/2025/03/airbnb-llm-test-migration/) · [Microsoft code-testing-generator](https://devblogs.microsoft.com/dotnet/polyglot-unit-testing-agent/) · [Diffblue](https://diffblue.com) |
| Agent / Skills 规范 | [Agent Skills 规范](https://agentskills.io/specification) · [anthropics/skills](https://github.com/anthropics/skills) · [github/awesome-copilot](https://github.com/github/awesome-copilot) |
| 测试工具 | [Karate](https://github.com/karatelabs/karate) · [Testcontainers GCloud](https://java.testcontainers.org/modules/gcloud) · [WireMock](https://wiremock.org/) · [Pact 对比 SCC](https://docs.pact.io/getting_started/comparisons) · [Schemathesis](https://schemathesis.readthedocs.io/) · [Microcks](https://microcks.io/) · [oasdiff](https://github.com/oasdiff/oasdiff) · [PIT](https://pitest.org/) · [Awaitility](https://github.com/awaitility/awaitility) · [ShedLock](https://github.com/lukas-krecan/ShedLock) · [jqwik](https://jqwik.net/) · [ApprovalTests.Java](https://github.com/approvals/ApprovalTests.Java) · [Instancio](https://github.com/instancio/instancio) · [fake-gcs-server](https://github.com/fsouza/fake-gcs-server) · [Keploy](https://github.com/keploy/keploy) |
| Spring / Beam | [Spring Batch testing](https://docs.spring.io/spring-batch/reference/testing.html) · [JobOperatorTestUtils](https://docs.spring.io/spring-batch/docs/6.0.x/api/org/springframework/batch/test/JobOperatorTestUtils.html) · [Spring Modulith testing](https://docs.spring.io/spring-modulith/reference/testing.html) · [Beam PAssert](https://beam.apache.org/releases/javadoc/current/org/apache/beam/sdk/testing/PAssert.html) |
