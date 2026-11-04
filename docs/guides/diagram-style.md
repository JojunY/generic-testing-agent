# Diagram style

Every diagram explains **one mechanism** and has **one accent**, on the thing the reader must notice. The visual language
follows [cathrynlavery/diagram-design](https://github.com/cathrynlavery/diagram-design): a paper, ink and muted palette
with a single accent, light and dark skins, a 4 px grid, hairline strokes and no shadows. Only the diagram types listed
below are used, not the reference's whole catalogue.

## Two formats

| Where | Format | Why |
|---|---|---|
| This repository's docs (README, plan, guides, AGENTS.md) | SVG rendered by `scripts/diagrams/render.py` into `docs/diagrams/<name>-{light,dark}.svg`, shown through `<picture>` | Full control of the style; diagrams are data, reviewable in PRs; CI runs `render.py --check` |
| Specs and analyst output in services | **Mermaid**, inside the `.md` | gtaw parses and lints it, it diffs in PRs, GitHub and IDEs render it in their own light or dark theme |

## Tokens (render.py)

| Token | Light | Dark | Use |
|---|---|---|---|
| `paper` | `#f5f5f5` | `#2d3142` | background |
| `ink` | `#2d3142` | `#f5f5f5` | titles and node names |
| `muted` | `#4f5d75` | `#bfc0c0` | strokes, sublabels, lifelines |
| `accent` | `#bf4520` | `#f08a59` | one node or path per diagram |
| `link` | `#2e5aa8` | `#739fdf` | cross-references only, never structure |
| `fill` / `group` | `#ffffff` / `#ebebeb` | `#363b4f` / `#333849` | node fill / group background |

| Property | Rule |
|---|---|
| Type | Three levels only: title in serif 24 (Instrument Serif, then Georgia), node name in sans 13 bold (Geist, then system sans), sublabels and notes in mono 11 (Geist Mono, then system mono). SVGs shown as images cannot load web fonts, so the fallbacks are what most readers see. |
| Grid | Every coordinate and size on a 4 px grid (`render.py` asserts it); 24 px outer margin; 24–56 px between nodes. |
| Lines | 0.8–1.2 px strokes. Solid for a call or data, dashed `4 4` for async, optional or a boundary, dotted `2 4` for lifelines. Small arrowheads. Straight or orthogonal routes; a detour only when a route loops back. |
| Shapes | Rounded rectangles (r 6) for components and files, pills for states, a dashed rectangle for a group or boundary. No icons, emoji, shadows or gradients. |
| Text | Sentence case, at most four words per node name, verbs on edges. |
| Accessibility | Text contrast at least 4.5:1 on both skins; every `<img>` has alt text that states the mechanism. |
| Size | At most about 12 nodes; split anything bigger. |

## Diagram types used

| Type | Used for | Diagrams |
|---|---|---|
| Architecture (boxes and groups) | what the parts are and who does what | `architecture` |
| Sequence (lifelines) | one step of the loop between orchestrator, gtaw, subagents and the developer | `loop` |
| State machine | item states, spec lifecycle | `item-states`, `spec-lifecycle` |
| Artifact map (data flow) | which files exist, who writes them, when they are committed | `artifacts` |
| Layers (dependency graph) | allowed import direction of gta-core | `layers` |

Not used: timelines, Gantt, pie, quadrant and Venn charts, mind maps, org charts, funnels, and the other decorative
types. Add a type only when a mechanism cannot be shown with these.

## Mermaid in specs

Allowed types per flow are in the analyst's reference (`core/skills/gta-writing-behavior-specs/references/diagrams.md`):

| Flow | Type |
|---|---|
| API, events | `sequenceDiagram` |
| Batch or scheduler steps, pipelines | `flowchart` (`TD` / `LR`) |
| Status or workflow | `stateDiagram-v2` |
| Data touched | `erDiagram` |

The diagram-design rules carry over:
- **No theme.** `%%{init}%%` overrides are rejected; the viewer's light or dark theme stays readable.
- **One accent**, through exactly these classes (the linter rejects any other `classDef`, `style` or `linkStyle`):
  - `classDef focus stroke:#bf4520,stroke-width:1.5px` on the entry point under test;
  - `classDef external stroke-dasharray:4 4` on downstream systems.
- **Structure:**
  - at most 40 lines (linted) and about 9 nodes or participants;
  - sentence-case labels and verbs on edges;
  - error branches are drawn, not left out.
- `%%` comments may come before the type line.
