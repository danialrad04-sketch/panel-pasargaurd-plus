---
name: dashboard-quality
description: "Implement or validate React/TypeScript dashboard changes, Persian RTL, accessibility, and admin screens."
---

# Dashboard Quality

Find the owning feature in `dashboard/src`, then follow its API client and query invalidation behavior. Reuse existing UI components and locale resources. Preserve `/dashboard/` routing. Verify loading, empty, error, and unauthorized states. For admin deposit actions use `billing-integrity` as well.

Run `npm run typecheck` and `npm run build` when dependencies are available and follow `build_dashboard.sh` for the dashboard-specific setup. For rendered changes, test keyboard interaction, focus and dialog dismissal, responsive layout, and Persian RTL using synthetic data. Record the viewport and observed outcome.
