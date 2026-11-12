from __future__ import annotations

from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .input import JsonNumber, scalar_string


OptionalString = Annotated[str, Field(max_length=4096)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    @field_validator("*", mode="after")
    @classmethod
    def strings_are_unicode_scalars(cls, value):
        return scalar_string(value) if isinstance(value, str) else value


class Site(StrictModel):
    id: OptionalString | None = None
    domain: OptionalString | None = None


class App(StrictModel):
    id: OptionalString | None = None
    bundle: OptionalString | None = None


class Device(StrictModel):
    ip: OptionalString | None = None
    os: OptionalString | None = None
    ua: OptionalString | None = None
    lmt: Annotated[int, Field(ge=0, le=1)] = 0

    @field_validator("lmt", mode="before")
    @classmethod
    def exact_numeric_lmt(cls, value):
        if isinstance(value, JsonNumber):
            return value.binary_value()
        if type(value) is int and value in (0, 1):
            return value
        raise ValueError("lmt requires an exact JSON number 0 or 1")


class User(StrictModel):
    id: OptionalString | None = None


class BidRequest(StrictModel):
    id: Annotated[str, Field(max_length=256)]
    site: Site | None = None
    app: App | None = None
    device: Device
    user: User | None = None

    @field_validator("id")
    @classmethod
    def nonblank_id(cls, value):
        if not any(character not in "\t\n\v\f\r " for character in value):
            raise ValueError("id must contain a non-ASCII-whitespace scalar")
        return value

    @model_validator(mode="after")
    def context_present(self):
        if self.site is None and self.app is None:
            raise ValueError("at least one non-null site or app is required")
        return self
