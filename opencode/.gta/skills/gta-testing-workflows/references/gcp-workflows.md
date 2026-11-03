# GCP Workflows (P4 for execution)

- There is no local emulator. The downstream HTTP services the workflow calls are replaced by WireMock deployed where Google can reach it.
- Deploy the workflow to a temporary name `gta-<shortRunId>-<purpose>`, run it through the Executions API, assert the result and the step history, then delete it.
- Until P4, test the services the workflow calls as APIs and keep the workflow definition's branches documented as scenarios with `hint.layer=system`.
