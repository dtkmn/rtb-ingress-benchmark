"""Raw v1 input checks before strict Pydantic validation."""
from __future__ import annotations

import json
import re


CONTENT_TYPE = re.compile(r'[ \t]*application/json[ \t]*(?:;[ \t]*charset[ \t]*=[ \t]*(?:utf-8|"utf-8")[ \t]*)?', re.IGNORECASE | re.ASCII)


def valid_headers(headers) -> bool:
    types = headers.getlist("content-type")
    encodings = headers.getlist("content-encoding")
    return (len(types) == 1 and CONTENT_TYPE.fullmatch(types[0]) is not None
            and (not encodings or len(encodings) == 1 and encodings[0].strip(" \t").lower() == "identity"))


class JsonNumber:
    __slots__ = ("raw",)

    def __init__(self, raw: str):
        self.raw = raw

    def binary_value(self) -> int:
        """Exact 0/1, including arbitrary exponents, without float rounding."""
        coefficient, separator, exponent = self.raw.lower().partition("e")
        negative = coefficient.startswith("-")
        coefficient = coefficient.removeprefix("-")
        whole, point, fraction = coefficient.partition(".")
        digits = (whole + fraction).lstrip("0")
        if not digits:
            return 0
        trailing = len(digits) - len(digits.rstrip("0"))
        if negative or digits.rstrip("0") != "1":
            raise ValueError("lmt must have exact mathematical value 0 or 1")
        expected = len(fraction) - trailing
        if not separator:
            exponent = "0"
        sign = "-" if exponent.startswith("-") else ""
        magnitude = exponent.lstrip("+-").lstrip("0") or "0"
        normalized = (sign if magnitude != "0" else "") + magnitude
        if normalized != str(expected):
            raise ValueError("lmt must have exact mathematical value 0 or 1")
        return 1


def scalar_string(value: str) -> str:
    if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
        raise ValueError("unpaired Unicode surrogate")
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        scalar_string(key)
        if key in result:
            raise ValueError("duplicate decoded object member")
        result[key] = value
    return result


def reject_constant(_value):
    raise ValueError("non-JSON numeric constant")


def decode_json(body: bytes | bytearray):
    text = body.decode("utf-8", errors="strict")
    if text.startswith("\ufeff"):
        raise ValueError("UTF-8 BOM is not permitted")
    return json.loads(text, object_pairs_hook=unique_object, parse_int=JsonNumber,
                      parse_float=JsonNumber, parse_constant=reject_constant)
