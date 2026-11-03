# OpenSpec compatibility

gta specs use the OpenSpec structure (`### Requirement:` with SHALL, `#### Scenario:` with WHEN/THEN) so teams that already use OpenSpec can read them. Differences:

- gta metadata lives in `<!-- gta: ... -->` comments (ids, sources, refs, hints); OpenSpec ignores them.
- gta specs live in `.gta/specs/<domain>/<flow>.spec.md`, not `openspec/specs/`.
- The OpenSpec CLI is not required (it needs Node and enables telemetry by default).

Reading existing OpenSpec or Spec Kit specs as `documented` sources (`spec.source=openspec|speckit`) arrives in P2. Until then, cite them with `source=documented doc=openspec/specs/orders/spec.md#create quote="..."`.
