use actix_web::http::header::{HeaderMap, CONTENT_ENCODING, CONTENT_TYPE};
use serde::{Deserialize, Deserializer};
use serde_json::value::RawValue;

fn hws(value: &str) -> &str {
    value.trim_matches([' ', '\t'])
}

fn media_type(value: &str) -> bool {
    let mut parts = value.split(';');
    if !hws(parts.next().unwrap_or("")).eq_ignore_ascii_case("application/json") {
        return false;
    }
    let Some(parameter) = parts.next() else {
        return true;
    };
    if parts.next().is_some() {
        return false;
    }
    let Some((name, value)) = parameter.split_once('=') else {
        return false;
    };
    hws(name).eq_ignore_ascii_case("charset")
        && (hws(value).eq_ignore_ascii_case("utf-8")
            || hws(value).eq_ignore_ascii_case("\"utf-8\""))
}

pub fn valid_headers(headers: &HeaderMap) -> bool {
    let mut types = headers.get_all(CONTENT_TYPE);
    let Some(content_type) = types.next() else {
        return false;
    };
    if types.next().is_some() || !content_type.to_str().is_ok_and(media_type) {
        return false;
    }
    let mut encodings = headers.get_all(CONTENT_ENCODING);
    match encodings.next() {
        None => true,
        Some(value) => {
            encodings.next().is_none()
                && value
                    .to_str()
                    .is_ok_and(|value| hws(value).eq_ignore_ascii_case("identity"))
        }
    }
}

/// Classify the exact decimal token, never a rounded f64. The zero shortcut and
/// normalized exponent comparison also support exponents larger than i64.
pub fn binary_number(raw: &str) -> Result<i32, &'static str> {
    if !raw.starts_with(|character: char| character == '-' || character.is_ascii_digit()) {
        return Err("lmt must be a JSON number");
    }
    let (coefficient, exponent) = raw.split_once(['e', 'E']).unwrap_or((raw, "0"));
    let negative = coefficient.starts_with('-');
    let coefficient = coefficient.strip_prefix('-').unwrap_or(coefficient);
    let (whole, fraction) = coefficient.split_once('.').unwrap_or((coefficient, ""));
    let digits = format!("{whole}{fraction}");
    let significant = digits.trim_start_matches('0');
    if significant.is_empty() {
        return Ok(0);
    }
    let trimmed = significant.trim_end_matches('0');
    if negative || trimmed != "1" {
        return Err("lmt must have exact mathematical value 0 or 1");
    }
    let expected = fraction.len() as i64 - (significant.len() - trimmed.len()) as i64;
    let magnitude = exponent
        .trim_start_matches(['-', '+'])
        .trim_start_matches('0');
    let magnitude = if magnitude.is_empty() { "0" } else { magnitude };
    let sign = if exponent.starts_with('-') && magnitude != "0" {
        "-"
    } else {
        ""
    };
    if format!("{sign}{magnitude}") != expected.to_string() {
        return Err("lmt must have exact mathematical value 0 or 1");
    }
    Ok(1)
}

pub fn deserialize_lmt<'de, D: Deserializer<'de>>(deserializer: D) -> Result<i32, D::Error> {
    let raw = Box::<RawValue>::deserialize(deserializer)?;
    binary_number(raw.get()).map_err(serde::de::Error::custom)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_number_never_rounds_and_supports_extreme_exponents() {
        for raw in ["0", "-0", "1.0", "1e-0", "0.00100e3", "1000e-3"] {
            assert!(binary_number(raw).is_ok(), "{raw}");
        }
        assert_eq!(binary_number(&format!("0e{}", "9".repeat(6000))), Ok(0));
        for raw in [
            "1.000000000000000001",
            "0.99999999999999999999",
            "-1",
            "1e-9999",
            "null",
            "true",
            "\"1\"",
        ] {
            assert!(binary_number(raw).is_err(), "{raw}");
        }
        assert!(binary_number(&format!("1e{}", "9".repeat(6000))).is_err());
    }
}
