# PasarGuard Plus engineering

- Android application: `android-client/` (Kotlin/Compose).
- Admin dashboard: `dashboard/` (React/TypeScript), built from the root package scripts.
- API and database: `app/` and `tests/` (FastAPI, SQLAlchemy, Alembic).
- Payment contract: `android-client/API_CONTRACT.md`. Confirm whether a route is registered and whether dependency injection uses the fake or live repository before reporting end-to-end support.

## Working as a team

For cross-component work, map shared contracts first. When subagents are available and parallel work is appropriate, use the project roles under `.codex/agents/`. Give each editing agent exclusive ownership of the paths it changes. Ask the billing reviewer or QA role for an independent read-only pass after integration. The parent resolves interface disagreements and verifies the combined change.

Use the matching skills in `.agents/skills/` for Android, dashboard, API, billing integrity, defensive review, and GitHub CI. Run the nearest existing checks for the changed surface. Report the exact command, its outcome, and any blocked verification. Use synthetic billing data in development and review.
