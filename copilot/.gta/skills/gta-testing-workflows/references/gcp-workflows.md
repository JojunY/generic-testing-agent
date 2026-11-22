# GCP Workflows

- There is no local emulator, and gta does not test deployed environments: it never deploys or runs a workflow.
- Test the services the workflow calls as APIs, with WireMock for their downstream HTTP calls.
- Record the workflow definition's own branches under `## Findings` as untested locally, for the team's SIT/UAT testing; they are not scenarios.
