---
name: github-ci-release
description: "Triage GitHub Actions failures, review a PR, verify migration or APK checks, or prepare a PasarGuard Plus release handoff."
---

# Github Ci Release

Read `.github/workflows/` and use the PR head SHA to find actual runs. Check path filters before treating a missing job as a failure. Diagnose the first actionable error and verify affected checks. Distinguish the Android debug and unsigned release artifacts. For Python, include relevant migration and Ruff workflows. For dashboard, include typecheck and build.

Write a PR handoff listing affected surfaces, exact checks and outcomes, outstanding verification, and rollback implications when relevant. Keep publishing, deployment, and release signing tied to the user's requested scope.
