#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Fold docs/01..10 + docs/README.md into docs/ALL_DOCS_COMBINED.md.

Run from the repo root:  python tools/make_combined.py

Why this exists: the combined file used to be pasted together by hand, so it
silently went stale (a doc gained new sections that never made it in). Now the
fold is one command, and `--check` prints a PASS/FAIL instead of writing.

The header and footer text is NOT hard-coded here on purpose: it is Persian
prose, and typing Persian into a tool channel on this machine has silently
injected Cyrillic/CJK homoglyphs before. On first run the existing preamble and
postamble are read out of the current combined file and reused byte for byte.
"""

import io
import os
import re
import sys

DOCS = "docs"
COMBINED = os.path.join(DOCS, "ALL_DOCS_COMBINED.md")
ORDER = [
    "01_PRD.md",
    "02_ARCHITECTURE.md",
    "03_DESIGN_SYSTEM.md",
    "04_BUSINESS_LOGIC_DEEP_DIVE.md",
    "05_AGENT_MASTER_PROMPT.md",
    "06_SPEC.md",
    "07_TASKS.md",
    "08_TEST_PLAN.md",
    "09_DEBUG_PLAN.md",
    "10_UI_FIX_PLAN.md",
    "README.md",
]

MARKER = "<!-- ==================== {name} ==================== -->"
RULE = "\n---\n"


def read(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def split_existing(text):
    """Return (preamble, postamble) taken from the current combined file.

    Preamble = everything before the first source marker.
    Postamble = everything after the last source block's trailing separator.
    Falls back to empty strings when the file does not exist yet.
    """
    first = text.find(MARKER.format(name=ORDER[0]))
    if first == -1:
        return "", ""
    preamble = text[:first]
    # The last marker is README.md; everything from its end onwards is footer
    # plus whatever followed the last folded file.
    last = text.rfind(MARKER.format(name=ORDER[-1]))
    tail = text[last:]
    # Drop the marker line and the folded file body; keep only what came after
    # the file, which is the closing note block.
    after_marker = tail.split("\n", 1)[1]
    # The folded README ends where the postamble begins; detect by the last
    # occurrence of the trailing rule that precedes the postamble.
    post = ""
    idx = after_marker.rfind(RULE)
    if idx != -1:
        post = after_marker[idx + len(RULE):]
    return preamble, post


def build(preamble, postamble):
    parts = [preamble.rstrip("\n"), ""]
    for name in ORDER:
        path = os.path.join(DOCS, name)
        if not os.path.exists(path):
            sys.stderr.write("MISSING source: %s\n" % path)
            return None
        body = read(path).strip("\n")
        parts.append(MARKER.format(name=name))
        parts.append("")
        parts.append(body)
        parts.append("")
        parts.append(RULE)
    if postamble.strip():
        parts.append(postamble.strip("\n"))
        parts.append("")
        parts.append(RULE)
    return "\n".join(parts)


def main():
    check_only = "--check" in sys.argv
    current = read(COMBINED) if os.path.exists(COMBINED) else ""
    preamble, postamble = split_existing(current)
    out = build(preamble, postamble)
    if out is None:
        return 2

    # Folded bytes per source, for the report line.
    detail = []
    for name in ORDER:
        body = read(os.path.join(DOCS, name))
        detail.append("%s=%d" % (name.split(".")[0], len(body.encode("utf-8"))))

    if check_only:
        if out == current:
            print("PASS  combined file is up to date (%d bytes)" % len(out.encode("utf-8")))
            return 0
        print("FAIL  combined file is stale")
        print("  on disk : %d bytes, mtime %s" % (len(current.encode("utf-8")), "old"))
        print("  would be: %d bytes" % len(out.encode("utf-8")))
        print("  folded  : " + " ".join(detail))
        return 1

    write(COMBINED, out)
    print("wrote %s (%d bytes)" % (COMBINED, len(out.encode("utf-8"))))
    print("  folded: " + " ".join(detail))
    return 0


if __name__ == "__main__":
    sys.exit(main())
