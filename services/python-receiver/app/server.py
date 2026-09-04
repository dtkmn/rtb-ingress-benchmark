from __future__ import annotations

import os

import uvicorn


def worker_count(raw: str | None = None) -> int:
    candidate = raw if raw is not None else os.getenv("HTTP_SERVER_WORKERS")
    try:
        workers = int(candidate or "1")
    except ValueError:
        workers = 1
    return workers if workers > 0 else 1


def main() -> None:
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8080,
        workers=worker_count(),
    )


if __name__ == "__main__":
    main()
