# Build the virtual environment against the same Debian/Python ABI used by
# the distroless runtime. The /usr/bin/python symlink ensures the virtualenv
# resolves its interpreter correctly after it is copied into the final image.
FROM python:3.14-slim-trixie AS builder

RUN ln -s /usr/local/bin/python /usr/bin/python \
    && /usr/bin/python -m venv /venv \
    && /venv/bin/pip install --no-cache-dir --upgrade pip setuptools wheel

COPY requirements.txt /requirements.txt
RUN /venv/bin/pip install \
    --no-cache-dir \
    --only-binary=:all: \
    --requirement /requirements.txt

FROM gcr.io/distroless/python3-debian13:nonroot

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY --from=builder --chown=nonroot:nonroot /venv /venv
COPY --chown=nonroot:nonroot app ./app

EXPOSE 8080

ENTRYPOINT ["/venv/bin/python3"]
CMD ["-m", "app.server"]
