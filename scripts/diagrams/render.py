#!/usr/bin/env python3
"""Renders the explanatory diagrams of README and plan into docs/diagrams/<name>-<theme>.svg.

Diagrams are data (nodes, edges, notes) so they stay reviewable in PRs and consistent in style. The style follows
cathrynlavery/diagram-design: one paper/ink/muted palette with a single accent, light and dark skins, a 4px grid,
hairline strokes, 6px radius, no shadows; serif titles, sans node names, mono sublabels (system fallbacks because an
SVG shown as an image cannot load web fonts).

Spec diagrams are NOT drawn here: they are Mermaid inside .spec.md files, parsed and linted by gtaw.

Usage: scripts/diagrams/render.py            # writes docs/diagrams/*.svg
       scripts/diagrams/render.py --check    # exits 1 if the committed SVGs are stale
"""
import sys
from html import escape
from pathlib import Path

THEMES = {
    "light": {"paper": "#f5f5f5", "ink": "#2d3142", "muted": "#4f5d75", "accent": "#bf4520", "link": "#2e5aa8",
              "fill": "#ffffff", "group": "#ebebeb"},
    "dark": {"paper": "#2d3142", "ink": "#f5f5f5", "muted": "#bfc0c0", "accent": "#f08a59", "link": "#739fdf",
             "fill": "#363b4f", "group": "#333849"},
}
SERIF = "'Instrument Serif', Georgia, 'Times New Roman', serif"
SANS = "Geist, Inter, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"
MONO = "'Geist Mono', SFMono-Regular, Consolas, 'Liberation Mono', monospace"
OUT = Path(__file__).resolve().parents[2] / "docs" / "diagrams"


def text_width(s, size, mono=False):
    return len(s) * size * (0.61 if mono else 0.56)


class Diagram:
    def __init__(self, name, title, width, height, subtitle=""):
        self.name, self.title, self.subtitle, self.width, self.height = name, title, subtitle, width, height
        self.nodes, self.groups, self.edges, self.notes, self.lifelines = {}, [], [], [], []

    def group(self, x, y, w, h, label):
        self.groups.append((x, y, w, h, label))

    def node(self, key, x, y, w, h, title, sub="", accent=False, round_=False):
        for v in (x, y, w, h):
            assert v % 4 == 0, f"{self.name}/{key}: {v} is off the 4px grid"
        self.nodes[key] = dict(x=x, y=y, w=w, h=h, title=title, sub=sub, accent=accent, round=round_)

    def anchor(self, key, side, offset=0):
        n = self.nodes[key]
        cx, cy = n["x"] + n["w"] / 2, n["y"] + n["h"] / 2
        return {"n": (cx + offset, n["y"]), "s": (cx + offset, n["y"] + n["h"]),
                "w": (n["x"], cy + offset), "e": (n["x"] + n["w"], cy + offset)}[side]

    def edge(self, points, label="", label_at=None, dashed=False, accent=False):
        self.edges.append(dict(points=points, label=label, label_at=label_at, dashed=dashed, accent=accent))

    def note(self, x, y, text, mono=False, anchor="start", muted=True):
        self.notes.append((x, y, text, mono, anchor, muted))

    def lifeline(self, x, y1, y2, title, accent=False):
        self.lifelines.append((x, y1, y2, title, accent))

    def svg(self, theme):
        t = THEMES[theme]
        o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.width} {self.height}" width="{self.width}" '
             f'height="{self.height}" role="img" aria-labelledby="title-{self.name}">',
             f'<title id="title-{self.name}">{escape(self.title)}</title>',
             '<defs>']
        for key, colour in (("ink", t["muted"]), ("accent", t["accent"])):
            o.append(f'<marker id="arrow-{key}" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="8" markerHeight="8" '
                     f'orient="auto-start-reverse"><path d="M0,0.8 L7,4 L0,7.2 z" fill="{colour}"/></marker>')
        o.append('</defs>')
        o.append(f'<rect width="100%" height="100%" fill="{t["paper"]}"/>')
        o.append(f'<text x="24" y="40" font-family="{SERIF}" font-size="24" fill="{t["ink"]}">{escape(self.title)}</text>')
        if self.subtitle:
            o.append(f'<text x="24" y="60" font-family="{MONO}" font-size="11" fill="{t["muted"]}">{escape(self.subtitle)}</text>')
        for x, y, w, h, label in self.groups:
            o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="{t["group"]}" stroke="{t["muted"]}" '
                     f'stroke-width="0.8" stroke-dasharray="4 4"/>')
            o.append(f'<text x="{x + 12}" y="{y + 20}" font-family="{MONO}" font-size="11" fill="{t["muted"]}">{escape(label)}</text>')
        for x, y1, y2, title, accent in self.lifelines:
            colour = t["accent"] if accent else t["muted"]
            o.append(f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" stroke="{colour}" stroke-width="0.8" stroke-dasharray="2 4"/>')
        for e in self.edges:
            colour = t["accent"] if e["accent"] else t["muted"]
            d = "M" + " L".join(f"{px},{py}" for px, py in e["points"])
            dash = ' stroke-dasharray="4 3"' if e["dashed"] else ""
            o.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="1"{dash} '
                     f'marker-end="url(#arrow-{"accent" if e["accent"] else "ink"})"/>')
        for n in self.nodes.values():
            stroke = t["accent"] if n["accent"] else t["ink"]
            width = 1.2 if n["accent"] else 0.8
            rx = n["h"] / 2 if n["round"] else 6
            o.append(f'<rect x="{n["x"]}" y="{n["y"]}" width="{n["w"]}" height="{n["h"]}" rx="{rx}" fill="{t["fill"]}" '
                     f'stroke="{stroke}" stroke-width="{width}"/>')
            cx = n["x"] + n["w"] / 2
            lines = [n["title"]] + ([n["sub"]] if isinstance(n["sub"], str) and n["sub"] else list(n["sub"] or []))
            total = 16 + (len(lines) - 1) * 15
            y = n["y"] + n["h"] / 2 - total / 2 + 12
            colour = t["accent"] if n["accent"] else t["ink"]
            o.append(f'<text x="{cx}" y="{y}" text-anchor="middle" font-family="{SANS}" font-size="13" font-weight="600" '
                     f'fill="{colour}">{escape(n["title"])}</text>')
            for i, sub in enumerate(lines[1:], start=1):
                o.append(f'<text x="{cx}" y="{y + i * 15}" text-anchor="middle" font-family="{MONO}" font-size="10.5" '
                         f'fill="{t["muted"]}">{escape(sub)}</text>')
            for line in lines:
                size, mono = (13, False) if line is n["title"] else (10.5, True)
                assert text_width(line, size, mono) <= n["w"] - 8, f"{self.name}: '{line}' does not fit in {n['w']}px"
        for e in self.edges:
            if not e["label"]:
                continue
            if e["label_at"]:
                lx, ly = e["label_at"]
            else:
                (x1, y1), (x2, y2) = e["points"][0], e["points"][1]
                lx, ly = (x1 + x2) / 2, (y1 + y2) / 2
            for i, part in enumerate(e["label"].split("\n")):
                w = text_width(part, 11, True) + 8
                yy = ly + i * 14
                o.append(f'<rect x="{lx - w / 2}" y="{yy - 10}" width="{w}" height="14" fill="{t["paper"]}"/>')
                colour = t["accent"] if e["accent"] else t["muted"]
                o.append(f'<text x="{lx}" y="{yy + 1}" text-anchor="middle" font-family="{MONO}" font-size="11" '
                         f'fill="{colour}">{escape(part)}</text>')
        for x, y, text, mono, anchor, muted in self.notes:
            o.append(f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-family="{MONO if mono else SANS}" '
                     f'font-size="{11 if mono else 12}" fill="{t["muted"] if muted else t["ink"]}">{escape(text)}</text>')
        o.append('</svg>')
        return "\n".join(o) + "\n"


def architecture():
    d = Diagram("architecture", "How gta fits together", 968, 520,
                "authored once in this repository · installed into each service · gtaw decides, agents write")
    d.group(24, 80, 384, 416, "this repository")
    d.node("core", 48, 112, 152, 64, "core/", ["agents · skills", "rules · registry"])
    d.node("platforms", 48, 200, 152, 56, "platforms/", "mapping.yaml")
    d.node("gen", 232, 148, 152, 64, "gta-harness-gen", "lint · generate")
    d.node("copilot", 48, 300, 152, 40, "copilot/", "")
    d.node("opencode", 232, 300, 152, 40, "opencode/", "")
    d.node("cli", 48, 404, 152, 64, "gta-cli", ["jar behind gtaw", "init · next · gate"])
    d.node("junit", 232, 404, 152, 64, "gta-junit", ["@KnownDefect", "scenario results"])
    d.edge([d.anchor("core", "e"), (216, 144), (216, 172), d.anchor("gen", "w", -8)])
    d.edge([d.anchor("platforms", "e"), (216, 228), (216, 188), d.anchor("gen", "w", 8)])
    d.edge([d.anchor("gen", "s", -40), (268, 280), (124, 280), d.anchor("copilot", "n")])
    d.edge([d.anchor("gen", "s", 40), d.anchor("opencode", "n", 40)])
    d.note(216, 364, "generated · committed · a team picks one", mono=True, anchor="middle")

    d.group(440, 80, 504, 416, "service repository")
    d.node("dev", 464, 104, 120, 40, "developer", "", round_=True)
    d.group(456, 164, 472, 128, "Copilot (VS Code · IntelliJ) or OpenCode")
    d.node("orch", 472, 204, 156, 64, "gta-orchestrator", ["/gta · relays", "never decides"])
    d.node("subs", 656, 196, 256, 80, "subagents", ["gta-analyst → spec", "gta-test-author → one test", "gta-reviewer → verdict"])
    d.node("gtaw", 472, 332, 220, 72, "gtaw", ["state · plan · change check", "test runs · oracle lock · gate"], accent=True)
    d.node("files", 724, 332, 188, 72, "service files", [".gta/specs/ (specs, shards)", "src/test/ (tests)"])
    d.node("maven", 472, 440, 100, 36, "Maven", "")
    d.node("git", 592, 440, 100, 36, "git", "")
    d.edge([d.anchor("dev", "s"), (524, 204)], "/gta · replies", label_at=(600, 156))
    d.edge([d.anchor("orch", "e"), d.anchor("subs", "w", 0)], "brief", label_at=(642, 228))
    d.edge([d.anchor("orch", "s", -30), (520, 332)], "next · answer", label_at=(520, 308), accent=True)
    d.edge([d.anchor("subs", "s", -60), (724, 304), (660, 304), (660, 332)], "check · submit", label_at=(692, 304), accent=True)
    d.edge([d.anchor("subs", "s", 60), (844, 332)], "write", label_at=(844, 308))
    d.edge([d.anchor("gtaw", "s", -58), (524, 440)], "")
    d.edge([d.anchor("gtaw", "s", 60), (642, 440)], "")
    d.note(708, 464, "runs tests · commits", mono=True)
    d.edge([d.anchor("opencode", "e"), (424, 320), (424, 124), d.anchor("dev", "w")], "gtaw init", label_at=(424, 220))
    return d


def loop():
    d = Diagram("loop", "One step of the loop", 968, 604,
                "gtaw returns the next node; the orchestrator hands it to an agent or to you, then asks again")
    lanes = {"dev": 120, "orch": 360, "gtaw": 600, "agent": 844}
    titles = {"dev": "developer", "orch": "gta-orchestrator", "gtaw": "gtaw", "agent": "subagent"}
    for key, x in lanes.items():
        d.node(key, x - 76, 80, 152, 40, titles[key], "", accent=(key == "gtaw"), round_=(key == "dev"))
        d.lifeline(x, 120, 588, titles[key], accent=(key == "gtaw"))

    def msg(y, a, b, label, accent=False, dashed=False):
        xa, xb = lanes[a], lanes[b]
        d.edge([(xa, y), (xb + (-4 if xb > xa else 4), y)], label, label_at=((xa + xb) / 2, y - 8), accent=accent, dashed=dashed)

    msg(160, "dev", "orch", "/gta order.create")
    msg(200, "orch", "gtaw", "./gtaw next", accent=True)
    d.node("decide", 512, 220, 176, 52, "deterministic step", ["state · plan · brief", "token"], accent=True)
    msg(300, "gtaw", "orch", "node: analyze | author | review", dashed=True)
    msg(340, "orch", "agent", "Follow the brief at …")
    msg(380, "agent", "gtaw", "./gtaw check   (free, repeatable)", accent=True)
    msg(420, "agent", "gtaw", "./gtaw submit  (counts an attempt)", accent=True)
    d.node("verify", 512, 436, 176, 52, "verify", ["scope · run · lock", "commit or triage"], accent=True)
    msg(508, "gtaw", "orch", "node: answer — prompt to show verbatim", dashed=True)
    msg(532, "orch", "dev", "prompt, verbatim", dashed=True)
    msg(556, "dev", "orch", "reply")
    msg(580, "orch", "gtaw", "./gtaw answer \"<reply>\" → next …", accent=True)
    return d


def item_states():
    d = Diagram("item-states", "Item states", 968, 532,
                "one test item from brief to commit · only gtaw moves an item · ● marks a human decision")
    row1, row2, row3 = 96, 236, 392
    d.node("pending", 24, row1, 136, 44, "PENDING", "")
    d.node("authoring", 196, row1, 136, 44, "AUTHORING", "brief + token")
    d.node("verifying", 380, row1, 148, 44, "VERIFYING", "scope · run · lock")
    d.node("reviewing", 576, row1, 148, 44, "REVIEWING", "oracle review")
    d.node("passed", 784, row1, 160, 44, "PASSED", "committed + shard", accent=True)
    d.node("repairing", 196, row2, 136, 44, "REPAIRING", "fix · submit again")
    d.node("triaging", 576, row2, 148, 44, "TRIAGING", "reviewer verdict")
    d.node("defect", 784, row2, 160, 44, "DEFECT_FOUND", "@KnownDefect pin")
    d.node("human", 380, row3, 148, 44, "● NEEDS_HUMAN", "escalation file")
    d.node("cancelled", 24, row3, 136, 44, "CANCELLED", "")
    d.edge([d.anchor("pending", "e"), d.anchor("authoring", "w")])
    d.edge([d.anchor("authoring", "e"), d.anchor("verifying", "w")], "submit", label_at=(356, 110))
    d.edge([d.anchor("verifying", "e"), d.anchor("reviewing", "w")], "pass", label_at=(552, 110))
    d.edge([d.anchor("reviewing", "e"), d.anchor("passed", "w")], "accept", label_at=(754, 110), accent=True)
    d.edge([d.anchor("verifying", "s", -40), (414, 258), d.anchor("repairing", "e", 0)], "mechanical\nfailure", label_at=(364, 194))
    d.edge([d.anchor("repairing", "n", 30), (294, 140)], "submit", label_at=(270, 190))
    d.edge([d.anchor("verifying", "s", 40), (494, 248), d.anchor("triaging", "w", -10)], "assertion · SUT · timeout\n(after 2 re-runs)", label_at=(584, 188))
    d.edge([d.anchor("reviewing", "s", 40), (690, 236)], "reject", label_at=(716, 220))
    d.edge([d.anchor("triaging", "w", 12), (336, 270)], "test-defect · flaky", label_at=(454, 286))
    d.edge([d.anchor("triaging", "s"), (650, 414), d.anchor("human", "e")], "product-defect ·\nspec-error · …", label_at=(650, 330))
    d.edge([d.anchor("repairing", "s"), (264, 414), d.anchor("human", "w")], "budget used ·\nsame failure twice", label_at=(264, 330))
    d.edge([d.anchor("human", "s", -40), (414, 476), (92, 476), (92, 440)], "B · cancel", label_at=(250, 476))
    d.edge([d.anchor("human", "s", 40), (494, 500), (12, 500), (12, 118), d.anchor("pending", "w")],
           "A · retry   or   A · confirm defect → pin", label_at=(250, 500))
    d.edge([d.anchor("verifying", "n", 30), (484, 84), (956, 84), (956, 258), d.anchor("defect", "e")],
           "pinned run of a confirmed defect", label_at=(720, 84), dashed=True)
    d.edge([d.anchor("passed", "s", 40), (904, 204), (744, 204), (744, 248), d.anchor("triaging", "e", -10)],
           "unstable at verify", label_at=(824, 204), dashed=True)
    return d


def spec_lifecycle():
    d = Diagram("spec-lifecycle", "Spec lifecycle", 968, 400,
                "a flow's behaviour spec, from code to approved expectations · ● marks a human decision")
    y = 136
    d.node("draft", 24, y, 132, 52, "draft", "analyst writes")
    d.node("questions", 200, y, 168, 52, "in-review · R1", "open questions")
    d.node("revise", 412, y, 152, 52, "revision", "answers applied")
    d.node("approval", 608, y, 152, 52, "in-review · R2", "ready for approval")
    d.node("approved", 804, y, 140, 52, "approved", "committed", accent=True)
    d.node("stale", 804, 284, 140, 52, "stale", "code changed")
    d.edge([d.anchor("draft", "e"), d.anchor("questions", "w")], "submit · lint", label_at=(178, 128))
    d.edge([d.anchor("questions", "e"), d.anchor("revise", "w")], "● R1: 1B …", label_at=(390, 128))
    d.edge([d.anchor("revise", "e"), d.anchor("approval", "w")], "submit", label_at=(586, 128))
    d.edge([d.anchor("approval", "e"), d.anchor("approved", "w")], "● approve", label_at=(782, 128), accent=True)
    d.edge([d.anchor("approved", "s"), d.anchor("stale", "n")], "gtaw next", label_at=(874, 240))
    d.edge([d.anchor("stale", "w"), (488, 310), d.anchor("revise", "s")], "re-analyse the changed refs", label_at=(640, 310))
    d.edge([d.anchor("approval", "n", -30), (654, 96), (458, 96), d.anchor("revise", "n", -30)],
           "● change request · edited after shown", label_at=(556, 96), dashed=True)
    d.note(24, 372, "Expected values are approved by a person before any test is written; gtaw owns status, rev, ticks and answers.")
    return d


DIAGRAMS = [architecture, loop, item_states, spec_lifecycle]


def main(check=False):
    stale = []
    OUT.mkdir(parents=True, exist_ok=True)
    for build in DIAGRAMS:
        d = build()
        for theme in THEMES:
            path = OUT / f"{d.name}-{theme}.svg"
            content = d.svg(theme)
            if check:
                if not path.exists() or path.read_text(encoding="utf-8") != content:
                    stale.append(str(path))
            else:
                path.write_text(content, encoding="utf-8")
                print("wrote", path.relative_to(OUT.parents[1]))
    if check and stale:
        print("stale diagrams (run scripts/diagrams/render.py):", *stale, sep="\n  ")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main("--check" in sys.argv))
