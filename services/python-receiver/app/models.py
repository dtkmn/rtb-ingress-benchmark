from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class Site(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str | None = None
    domain: str | None = None


class App(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str | None = None
    bundle: str | None = None


class Device(BaseModel):
    model_config = ConfigDict(extra="ignore")

    ip: str | None = None
    os: str | None = None
    ua: str | None = None
    lmt: int = 0


class User(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str | None = None


class BidRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str = ""
    site: Site | None = None
    app: App | None = None
    device: Device | None = None
    user: User | None = None
