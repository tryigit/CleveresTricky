# CleveresTricky Versioning Rules

When bumping versions for the CleveresTricky module, do NOT touch `update.json`. You must leave `update.json` completely unmodified.
Only update `build.gradle.kts` (e.g., `val verName by extra(...)`) and any other necessary files, but explicitly skip `update.json`.

## Branch Lifecycle Rules

Feature, fix, experiment, and AI-generated branches are temporary and must not be kept after their work is integrated.

- After a pull request is successfully merged into `master`, delete its source branch immediately.
- Remove stale branches whose changes have already been merged into `master`.
- Do not delete `master` or any branch that still contains unmerged work.
- Keep a merged branch only when there is an explicit, documented reason for it to remain long-lived.
