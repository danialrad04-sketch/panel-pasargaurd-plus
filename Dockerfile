# syntax=docker/dockerfile:1
ARG PYTHON_VERSION=3.14

FROM oven/bun:1 AS bun
FROM node:24-bookworm-slim AS frontend
COPY --from=bun /usr/local/bin/bun /usr/local/bin/bun
WORKDIR /frontend
COPY dashboard/package.json dashboard/bun.lock ./
RUN --mount=type=secret,id=proxy_ca --mount=type=cache,target=/root/.bun/install/cache \
    if [ -f /run/secrets/proxy_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/proxy_ca SSL_CERT_FILE=/run/secrets/proxy_ca; fi; \
    bun install --frozen-lockfile
COPY dashboard/ ./
ARG VITE_BASE_API=/
ENV VITE_BASE_API=$VITE_BASE_API
RUN bun run build && cp build/index.html build/404.html

FROM ghcr.io/astral-sh/uv:python$PYTHON_VERSION-bookworm-slim AS builder
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    python3-dev \
    libc6-dev \
    && rm -rf /var/lib/apt/lists/*

ENV UV_PYTHON_DOWNLOADS=0

WORKDIR /build
RUN --mount=type=cache,target=/root/.cache/uv \
    --mount=type=secret,id=proxy_ca \
    --mount=type=bind,source=uv.lock,target=uv.lock \
    --mount=type=bind,source=pyproject.toml,target=pyproject.toml \
    if [ -f /run/secrets/proxy_ca ]; then export SSL_CERT_FILE=/run/secrets/proxy_ca UV_NATIVE_TLS=true; fi; \
    uv sync --frozen --no-install-project --no-dev
ADD . /build
RUN --mount=type=cache,target=/root/.cache/uv \
    --mount=type=secret,id=proxy_ca \
    if [ -f /run/secrets/proxy_ca ]; then export SSL_CERT_FILE=/run/secrets/proxy_ca UV_NATIVE_TLS=true; fi; \
    uv sync --frozen --no-dev && rm -rf /build/dashboard


FROM python:$PYTHON_VERSION-slim-bookworm

COPY --from=builder /build /code
COPY dashboard/__init__.py /code/dashboard/__init__.py
COPY --from=frontend /frontend/build /code/dashboard/build
WORKDIR /code

ENV PATH="/code/.venv/bin:$PATH"

# Keep the runtime trust store explicit. Outbound notification clients use it
# without replacing Python's process-wide SSLContext.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    unbound \
    dns-root-data \
    && update-ca-certificates \
    && install -d -o unbound -g unbound /var/lib/unbound \
    && cp /usr/share/dns/root.key /var/lib/unbound/root.key \
    && chown unbound:unbound /var/lib/unbound/root.key \
    && rm -rf /var/lib/apt/lists/*

RUN install -m 755 /code/cli_wrapper.sh /usr/bin/pasarguard-cli \
    && install -m 755 /code/tui_wrapper.sh /usr/bin/pasarguard-tui \
    && chmod +x /code/healthcheck.sh /code/start.sh

ENTRYPOINT ["/code/start.sh"]
