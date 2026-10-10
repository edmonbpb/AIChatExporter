# Antigravity E2E inbox

This folder is the defect channel from an outside agent (Antigravity / Gemini) back into the
Coder session. It is created by the plan at `AGY-E2E-PLAN.md` in the repository root.

**What lands here**
- `defect-<NN>-<slug>.md` - one file per defect, in the exact template of section 7 of the plan.
- `step<N>-*.xml` / `step<N>-*.png` - the raw evidence the agent cites (screen dumps, screenshots).
- `run-summary.txt` - the agent own one-line result, copied from its final reply.

**How Coder uses it**
1. Read `run-summary.txt` first: it carries the verdict counts.
2. Then read each `defect-*.md`. For every one, verify the quoted evidence exists before
   believing it; a defect report that does not reproduce is not a defect.
3. Adjudicated findings become rows in `docs/09_DEBUG_PLAN.md` with a number, never a free-float note.

**House rule**
The outside agent reports; the Coder session fixes. Nothing in this folder is a source change.
