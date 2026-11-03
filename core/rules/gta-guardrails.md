## gta (Generic Testing Agent)

This repository uses gta for spec-first tests. For anything about writing, fixing or explaining tests, use `/gta`.
Do not edit files under `.gta/` by hand (specs are reviewed through `/gta`; `.gta/work/` is rebuildable state), and do not change tests tagged `gta` to match new behaviour without updating their spec — the gate compares them with the base branch.
