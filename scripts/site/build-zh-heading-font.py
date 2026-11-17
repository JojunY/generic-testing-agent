#!/usr/bin/env python3
"""Builds the font of the Chinese headings of the introduction site (docs/site/index.zh-CN.html).

The headings (h1, h2 and the verbs of the roles) are set in Noto Serif SC, weight 600. A whole Chinese font is several
megabytes, so the site ships only the characters its headings use: docs/site/assets/fonts/noto-serif-sc-head.woff2,
and next to it noto-serif-sc-head.txt, the list of those characters as code points. RepositoryHarnessTest fails when
a heading uses a character that is not in the list: run this script again after a change to a Chinese heading.

Needs: npm (to fetch @fontsource-variable/noto-serif-sc, SIL Open Font License 1.1) and Python with fonttools and
brotli (pip install fonttools brotli). Usage: scripts/site/build-zh-heading-font.py
"""
import html.parser
import pathlib
import subprocess
import sys
import tarfile
import tempfile

from fontTools import subset
from fontTools.merge import Merger
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

REPO = pathlib.Path(__file__).resolve().parents[2]
PAGE = REPO / "docs/site/index.zh-CN.html"
FONTS = REPO / "docs/site/assets/fonts"
OUT = FONTS / "noto-serif-sc-head.woff2"
LIST = FONTS / "noto-serif-sc-head.txt"
PACKAGE = "@fontsource-variable/noto-serif-sc@5.3.1"
WEIGHT = 600


class Headings(html.parser.HTMLParser):
    """Collects the text of h1, h2 and of the elements with the class role-verb."""

    def __init__(self):
        super().__init__()
        self.depth = 0
        self.stack = []
        self.text = []

    def handle_starttag(self, tag, attrs):
        classes = (dict(attrs).get("class") or "").split()
        heading = tag in ("h1", "h2") or "role-verb" in classes
        self.stack.append(heading)
        self.depth += heading

    def handle_endtag(self, tag):
        if self.stack:
            self.depth -= self.stack.pop()

    def handle_data(self, data):
        if self.depth:
            self.text.append(data)


def needed():
    """The characters the Latin font does not have: everything from U+3000 on (Chinese and full-width punctuation)."""
    parser = Headings()
    parser.feed(PAGE.read_text(encoding="utf-8"))
    return sorted({c for c in "".join(parser.text) if ord(c) >= 0x3000})


def fetch(work):
    subprocess.run(["npm", "pack", PACKAGE, "--silent"], cwd=work, check=True, stdout=subprocess.DEVNULL)
    archive = next(work.glob("*.tgz"))
    with tarfile.open(archive) as tar:
        tar.extractall(work, filter="data")
    return work / "package"


def main():
    chars = needed()
    if not chars:
        sys.exit("no Chinese heading text found in " + str(PAGE))
    codes = {ord(c) for c in chars}
    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)
        package = fetch(work)
        parts = []
        for chunk in sorted((package / "files").glob("noto-serif-sc-*-wght-normal.woff2")):
            font = TTFont(chunk)
            here = codes & set(font.getBestCmap())
            if not here:
                continue
            font = instancer.instantiateVariableFont(font, {"wght": WEIGHT})
            options = subset.Options()
            options.layout_features = ["*"]
            options.name_IDs = ["*"]
            options.notdef_outline = True
            sub = subset.Subsetter(options)
            sub.populate(unicodes=here)
            sub.subset(font)
            font.flavor = None
            path = work / ("part-%d.ttf" % len(parts))
            font.save(path)
            parts.append(str(path))
        merged = Merger().merge(parts)
        covered = codes & set(merged.getBestCmap())
        missing = sorted(codes - covered)
        if missing:
            sys.exit("Noto Serif SC has no glyph for " + ", ".join("U+%04X" % c for c in missing))
        merged.flavor = "woff2"
        merged.save(OUT)
        licence = (package / "LICENSE").read_text(encoding="utf-8")
    copyright_line = merged["name"].getDebugName(0) or ""
    if licence.startswith("Google Inc.\n") and copyright_line:  # the package's file lacks the font's own notice
        licence = copyright_line + "\n" + licence[len("Google Inc.\n"):]
    (FONTS / "OFL-Noto-Serif-SC.txt").write_text(licence, encoding="utf-8")
    LIST.write_text(
        "# Characters in noto-serif-sc-head.woff2, written by scripts/site/build-zh-heading-font.py\n"
        + "".join("U+%04X\n" % c for c in sorted(covered)),
        encoding="utf-8",
    )
    print("%d characters, %d bytes: %s" % (len(covered), OUT.stat().st_size, OUT.relative_to(REPO)))


if __name__ == "__main__":
    main()
