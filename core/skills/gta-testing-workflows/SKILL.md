---
name: gta-testing-workflows
description: Tests orchestrated flows - GCP Workflows definitions, Spring State Machine, Temporal and Camunda processes - by covering every transition, error branch and compensation path derived from the spec's state diagram. Use when a gta author brief targets a workflow scenario (P3).
---

# Testing workflows

## Quick start

Derive tests from the spec's `stateDiagram-v2`: one test per edge, plus every error branch and compensation.

## Workflow

1. Spring State Machine: `StateMachineTestPlanBuilder` — send events, expect states and transitions (`references/state-machines.md`).
2. Temporal: `TestWorkflowEnvironment` with time skipping and mocked activities; Camunda: the engine's test support with mocked delegates.
3. GCP Workflows: no local emulator. Unit-test the services the workflow calls; executing the workflow itself is a P4 system test (`references/gcp-workflows.md`).
4. Assert the final state and the history (which steps ran, in which order), and that compensations undo partial work.

## Rules

- Every state in the diagram must be reachable in some test; every forbidden event must be rejected in at least one test.

## References

- `references/gcp-workflows.md` · `references/state-machines.md`
