---
name: gta
description: Run the Generic Testing Agent - explain a flow, write tests for a flow, continue where you left off, or show status.
agent: gta-orchestrator
---

Run the gta loop for this repository. Arguments from the user: {{args}}

- `explain <flowId|file>` — write a behaviour spec and questions only (read-only for code)
- `<flowId>` — spec, questions, approval, then tests for that flow
- `status` — show flows, items and what is waiting for the human
- nothing — continue the current work, or list the riskiest flows to choose from

Follow your agent instructions: call `./gtaw next` (Windows: `.\gtaw.cmd next`), dispatch nodes, show gtaw prompts verbatim, and pass the human's replies verbatim to `./gtaw answer`.
