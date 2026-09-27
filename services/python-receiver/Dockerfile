# Reuse the same interpreter and Debian ABI in both stages. Debian 13's
# distroless Python is 3.13, but this application requires Python 3.14.
FROM python:3.14.7-slim-trixie AS python-base

FROM python-base AS builder

COPY --from=ghcr.io/astral-sh/uv:0.12.19 /uv /uvx /usr/local/bin/

ENV UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/venv

WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync \
    --frozen \
    --no-dev \
    --no-install-project \
    --python /usr/local/bin/python

FROM python-base AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY --from=builder --chown=65532:65532 /venv /venv
COPY --chown=65532:65532 app ./app

USER 65532:65532

EXPOSE 8080

ENTRYPOINT ["/venv/bin/python3"]
CMD ["-m", "app.server"]
