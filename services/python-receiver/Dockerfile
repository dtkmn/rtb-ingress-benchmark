# Match the Python minor version and Debian ABI supplied by distroless.
FROM python:3.13.15-slim-trixie AS builder

COPY --from=ghcr.io/astral-sh/uv:0.12.19 /uv /uvx /usr/local/bin/

ENV UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/venv

WORKDIR /app
COPY pyproject.toml uv.lock ./
# Point the copied venv at distroless's interpreter and standard library.
RUN uv sync \
    --frozen \
    --no-dev \
    --no-install-project \
    --python /usr/local/bin/python \
    && ln -sf /usr/bin/python3.13 /venv/bin/python \
    && sed -i 's|^home = .*|home = /usr/bin|' /venv/pyvenv.cfg

FROM gcr.io/distroless/python3-debian13:nonroot AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY --from=builder --chown=65532:65532 /venv /venv
COPY --chown=65532:65532 app ./app

USER 65532:65532

EXPOSE 8080

ENTRYPOINT ["/venv/bin/python3"]
CMD ["-m", "app.server"]
