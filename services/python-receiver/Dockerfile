# Build the virtual environment against the same Debian/Python ABI used by
# the distroless runtime. The /usr/bin/python symlink ensures the virtualenv
# resolves its interpreter correctly after it is copied into the final image.
FROM python:3.14-slim-trixie AS builder

COPY --from=ghcr.io/astral-sh/uv:0.9.6 /uv /uvx /usr/local/bin/

RUN ln -s /usr/local/bin/python /usr/bin/python

WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync \
    --frozen \
    --no-dev \
    --no-install-project \
    --python /usr/bin/python

FROM gcr.io/distroless/python3-debian13:nonroot

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY --from=builder --chown=nonroot:nonroot /app/.venv /venv
COPY --chown=nonroot:nonroot app ./app

EXPOSE 8080

ENTRYPOINT ["/venv/bin/python3"]
CMD ["-m", "app.server"]