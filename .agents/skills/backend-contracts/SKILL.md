---
name: backend-contracts
description: "Develop or review FastAPI endpoints, authorization, persistence, API schema, and Alembic migrations."
---

# Backend Contracts

Trace the request through `app/` router, dependency, service and persistence layers. Verify existing role and resource scopes for every changed route. Preserve response compatibility and check generated dashboard clients if OpenAPI changes. For financial transitions, use `billing-integrity`.

Use the Python version and dependencies in `pyproject.toml` and `uv.lock`. Run focused tests with `uv run pytest`, plus `uv run ruff check --no-fix` for changed Python paths when available. For schema changes, inspect `.github/workflows/test-database-migrations.yml`, verify upgrades from existing data, and check supported database behavior.
