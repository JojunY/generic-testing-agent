# gta — 通用测试 Agent（Generic Testing Agent）

[English](README.md) | **简体中文**

gta 是一个面向 Java/Spring 服务、"先写规格再写测试"的测试 harness，运行在 **GitHub Copilot**（VS Code、IntelliJ）或 **OpenCode** 中。

> **现状：** 确定性的部分 `gtaw` 有单元测试和六个脚本化的端到端运行覆盖，其中由脚本扮演 agent。目前还没有用真实模型在真实服务上运行的记录：请先看[试点指南](docs/guides/pilot.md)。第一次接触？用浏览器打开[介绍页](docs/site/index.zh-CN.html)（本地文件，不需要服务器），或先读[十分钟了解 gta](docs/design/overview.md)（英文）；[docs/README.md](docs/README.md) 说明每份文档给谁看。

输入 `/gta` 后，它按以下循环工作：

1. 读取一个 flow（一个 REST 接口、一个定时任务等）的代码。
2. 写出一份可评审的行为规格（spec）。
3. 对不确定或可疑的地方向你提问。
4. 你批准规格后，它编写回归测试和集成测试，期望值来自规格，而不是代码当前的输出。

覆盖率不是目标。测试应当检查服务*应该*做什么，发现回归，并把缺陷暴露出来，而不是把缺陷锁定成"正确"。

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/architecture-dark.svg">
  <img alt="gta 的组成：core/ 和 platforms/ 生成 copilot/ 与 opencode/；在服务中，orchestrator 在开发者、subagent 与 gtaw 之间转达，gtaw 负责运行测试、锁定期望值并提交。" src="docs/diagrams/architecture-light.svg">
</picture>

```
/gta borrow.book
  → analyst 写出 .gta/specs/borrow/book.spec.md（EARS 需求、场景、Mermaid 图、问题）
  → 每个问题都带推荐答案：你回复 "OK"（或 "OK, 2B"），再回复一次 "OK" 批准 R2
  → test author 编写 BorrowControllerIT；gtaw 运行它、锁定期望值并提交
  → 与已确认期望不符的失败测试进入分流；确认的缺陷用 @KnownDefect 标注
  → 会话结束：稳定性运行、基于目标分支的质量门禁和报告
```

## 组成

| 部分 | 作用 |
|---|---|
| `gtaw`（Java CLI，`toolkit/gta-cli`） | 完成所有确定性步骤：状态、计划、测试运行、失败分流、期望值锁定、改动检查、提交、门禁、报告。只有 `gtaw` 能判定测试通过。 |
| 4 个 agent（`core/agents`） | `gta-orchestrator`（入口）、`gta-analyst`（写规格）、`gta-test-author`（每份 brief 写一个测试）、`gta-reviewer`（只读：期望值评审与分流）。 |
| 24 个 skill（`core/skills`） | 测试知识，按路径从 `.gta/skills/` 加载：Spring API、WireMock、不用容器的依赖替身（Oracle、BigQuery、Pub/Sub、Kafka、JMS/MQ）、Batch、定时任务、Beam 等。 |
| `gta-junit`（`toolkit/gta-junit`） | `@KnownDefect`（严格的预期失败）和把测试结果对应到场景的 listener。 |
| `copilot/`、`opencode/` | 可直接安装的 harness 文件夹，由 `gta-harness-gen` 从 `core/` + `platforms/` 生成。 |

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/loop-dark.svg">
  <img alt="循环的一步：orchestrator 向 gtaw 获取下一个节点，交给 subagent 或开发者，再把 check、submit、answer 交回 gtaw。" src="docs/diagrams/loop-light.svg">
</picture>

完整设计见 [`docs/design/plan.zh-CN.md`](docs/design/plan.zh-CN.md)（[English](docs/design/plan.md)）。

## 安装

gta 只有一个版本，区别只在 jar 从哪里来。两种方式装进你自己服务的是同一套 harness。

| | 本地安装（不需要 Nexus） | 团队安装 |
|---|---|---|
| 适合 | 一个开发者评估或使用 gta | 所有参与该服务的人，以及 CI |
| jar 的来源 | 本仓库，在本地构建 | 共享地址（Nexus，或 GitHub Release） |
| 做法 | `scripts/install-local.sh <service> [copilot\|opencode]` | [docs/guides/quickstart.zh-CN.md](docs/guides/quickstart.zh-CN.md) 第 1 到 3 步 |

```bash
scripts/install-local.sh path/to/your-service          # 构建一次 gta，安装，并运行 ./gtaw doctor
```

然后在 VS Code、IntelliJ 或 OpenCode 中打开服务，输入 `/gta`。

本地安装把 wrapper 固定到你机器上的路径，`gta-junit` 也只在你的 `~/.m2` 里，所以同事和 CI 用不了。要改成团队安装，用共享地址再运行一次 `init`（快速开始第 2 步）即可，其他都不用变。

## 当前支持

- **环境：** Maven 和 Gradle 服务，Spring Boot 4.1 和 3.5（也支持 2.2 到 2.7），Java 17+（少数 Java 8 或 11 的服务也支持），Windows、macOS、Linux。gtaw 本身需要 JDK 17+。
- **会写测试的能力：** REST API（含安全规则和缓存）、消息监听（Kafka、Pub/Sub、JMS/MQ、RabbitMQ、SQS）、定时任务（`@Scheduled`、Quartz）、Spring Batch 作业、Flyway/Liquibase 数据库迁移、WebFlux 端点、gRPC 服务、GraphQL handler、工作流（Temporal、Spring Statemachine、BPMN）和 Beam 转换。WebSocket、STOMP 和 SSE 端点，Spring Cloud Function（包括 Cloud Run functions）、Spring Cloud Stream 函数、Spring Integration flow，并覆盖 Resilience4j 和 Spring Retry 的行为、用假客户端替代 Secret Manager（P4）。服务本来就在用 Cucumber 时，场景写成 feature 文件（设计文档 §7.10）。gta 只在本地运行测试：从不测试部署环境（SIT、UAT、生产环境），每次运行都会去掉你 shell 里的 Spring profile 和云凭证，agent 的规则也要求测试只用本地依赖（见设计文档 §7.7、§13）。
- **不需要容器：**
  - Oracle 使用共享测试 schema，或 Oracle 模式的 H2；
  - BigQuery 和 GCS 使用替身；
  - Kafka 和 JMS 嵌入式运行；
  - Pub/Sub 使用从 Nexus 下载的模拟器（P3）。

## 仓库结构

```
core/        harness 内容的唯一来源：agent、命令、规则、skill、模板、schema、能力注册表
platforms/   copilot/ 与 opencode/ 的映射（平台差异只在这里）
copilot/     由 core/ + platforms/copilot 生成（已提交，供团队选用），不要手改
opencode/    由 core/ + platforms/opencode 生成（已提交，供团队选用），不要手改
toolkit/     Java 源码：gta-core、gta-cli、gta-junit、gta-harness-gen（Maven 多模块；版本号以 pom.xml 为准）
wrapper/     安装到服务中的文件：gtaw、gtaw.cmd、gtaw-download.ps1（从 Nexus 下载锁定版本的 jar 并校验 sha256）
scripts/     install-local.sh（不经 Nexus 安装到服务）、generate.sh（平台文件夹）、diagrams/render.py（docs/diagrams）、site/record-demo.sh（录制 docs/site 的重放数据）、site/build-zh-heading-font.py（生成其中文标题字体）
evals/       带预埋缺陷的 order-service 样例和脚本化端到端测试
docs/        guides/（快速开始、试点、CI 接入、测试命名、图表风格、新增能力）、design/（overview：从这里开始；plan：完整设计）、archive/（历史评审记录）、diagrams/、site/（介绍页，纯静态）
```

## 开发

```bash
mvn -B install                       # 构建 + 单元测试（同时检查 copilot/ 与 opencode/ 是否同步）
scripts/generate.sh                  # 修改 core/ 或 platforms/ 后重新生成 copilot/ 与 opencode/
scripts/diagrams/render.py           # 修改示意图后重新生成 docs/diagrams（CI 中用 --check）
evals/e2e/run.sh                     # 针对 evals/fixtures/order-service 的脚本化端到端测试
```

贡献规则见 [AGENTS.md](AGENTS.md)（英文）。
