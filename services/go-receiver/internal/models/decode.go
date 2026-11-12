package models

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"strconv"
	"strings"
	"unicode/utf8"
)

var (
	ErrInvalidJSON   = errors.New("invalid json payload")
	ErrInvalidSchema = errors.New("bad request")
)

// DecodeBidRequest decodes once into the canonical model. Token decoding retains
// number lexemes and decoded member names, avoiding struct coercion, case-folded
// field matching and the last-member-wins behavior of ordinary JSON unmarshaling.
func DecodeBidRequest(body []byte) (BidRequest, error) {
	var request BidRequest
	if !utf8.Valid(body) || bytes.HasPrefix(body, []byte{0xef, 0xbb, 0xbf}) || !validStringEscapes(body) {
		return request, ErrInvalidJSON
	}
	d := &requestDecoder{json.NewDecoder(bytes.NewReader(body))}
	d.decoder.UseNumber()
	first, err := d.token()
	if err != nil {
		return request, err
	}
	err = d.object(first, func(name string) error {
		var err error
		switch name {
		case "id":
			var value json.Token
			value, err = d.token()
			if err != nil {
				return err
			}
			id, ok := value.(string)
			if !ok || utf8.RuneCountInString(id) > 256 || asciiBlank(id) {
				return ErrInvalidSchema
			}
			request.ID = id
		case "site":
			request.Site, err = d.site()
		case "app":
			request.App, err = d.app()
		case "device":
			request.Device, err = d.device()
		case "user":
			request.User, err = d.user()
		default:
			return ErrInvalidSchema
		}
		return err
	})
	if err != nil {
		return request, err
	}
	if _, err = d.decoder.Token(); err != io.EOF {
		return request, ErrInvalidJSON
	}
	if request.ID == "" || request.Device == nil || (request.Site == nil && request.App == nil) {
		return request, ErrInvalidSchema
	}
	return request, nil
}

type requestDecoder struct{ decoder *json.Decoder }

func (d *requestDecoder) token() (json.Token, error) {
	value, err := d.decoder.Token()
	if err != nil {
		return nil, ErrInvalidJSON
	}
	return value, nil
}

func (d *requestDecoder) object(first json.Token, field func(string) error) error {
	if first != json.Delim('{') {
		return ErrInvalidSchema
	}
	seen := make(map[string]struct{})
	for d.decoder.More() {
		key, err := d.token()
		if err != nil {
			return err
		}
		name, ok := key.(string)
		if !ok {
			return ErrInvalidJSON
		}
		if _, duplicate := seen[name]; duplicate {
			return ErrInvalidJSON
		}
		seen[name] = struct{}{}
		if err = field(name); err != nil {
			return err
		}
	}
	end, err := d.token()
	if err != nil || end != json.Delim('}') {
		return ErrInvalidJSON
	}
	return nil
}

func (d *requestDecoder) optionalString() (*string, error) {
	value, err := d.token()
	if err != nil {
		return nil, err
	}
	if value == nil {
		return nil, nil
	}
	text, ok := value.(string)
	if !ok || utf8.RuneCountInString(text) > 4096 {
		return nil, ErrInvalidSchema
	}
	return &text, nil
}

func (d *requestDecoder) site() (*Site, error) {
	first, err := d.token()
	if err != nil || first == nil {
		return nil, err
	}
	site := &Site{}
	err = d.object(first, func(name string) error {
		var err error
		switch name {
		case "id":
			site.ID, err = d.optionalString()
		case "domain":
			site.Domain, err = d.optionalString()
		default:
			return ErrInvalidSchema
		}
		return err
	})
	return site, err
}

func (d *requestDecoder) app() (*App, error) {
	first, err := d.token()
	if err != nil || first == nil {
		return nil, err
	}
	app := &App{}
	err = d.object(first, func(name string) error {
		var err error
		switch name {
		case "id":
			app.ID, err = d.optionalString()
		case "bundle":
			app.Bundle, err = d.optionalString()
		default:
			return ErrInvalidSchema
		}
		return err
	})
	return app, err
}

func (d *requestDecoder) device() (*Device, error) {
	first, err := d.token()
	if err != nil {
		return nil, err
	}
	device := &Device{}
	err = d.object(first, func(name string) error {
		var err error
		switch name {
		case "ip":
			device.IP, err = d.optionalString()
		case "os":
			device.OS, err = d.optionalString()
		case "ua":
			device.UA, err = d.optionalString()
		case "lmt":
			var value json.Token
			value, err = d.token()
			if err != nil {
				return err
			}
			number, ok := value.(json.Number)
			if !ok {
				return ErrInvalidSchema
			}
			var valid bool
			device.LMT, valid = exactLMT(string(number))
			if !valid {
				return ErrInvalidSchema
			}
		default:
			return ErrInvalidSchema
		}
		return err
	})
	return device, err
}

func (d *requestDecoder) user() (*User, error) {
	first, err := d.token()
	if err != nil || first == nil {
		return nil, err
	}
	user := &User{}
	err = d.object(first, func(name string) error {
		if name != "id" {
			return ErrInvalidSchema
		}
		var err error
		user.ID, err = d.optionalString()
		return err
	})
	return user, err
}

func asciiBlank(id string) bool {
	for _, r := range id {
		if r != ' ' && (r < '\t' || r > '\r') {
			return false
		}
	}
	return true
}

// exactLMT receives a grammar-checked JSON number. Normalizing its coefficient
// and exponent determines equality to 0/1 without floats, arbitrary precision
// allocations, or expanding hostile exponents within the bounded request body.
func exactLMT(number string) (int, bool) {
	negative := strings.HasPrefix(number, "-")
	number = strings.TrimPrefix(number, "-")
	mantissa, exponent := number, "0"
	if e := strings.IndexAny(number, "eE"); e >= 0 {
		mantissa, exponent = number[:e], number[e+1:]
	}
	fractionDigits := 0
	if dot := strings.IndexByte(mantissa, '.'); dot >= 0 {
		fractionDigits = len(mantissa) - dot - 1
	}
	digits := strings.ReplaceAll(mantissa, ".", "")
	digits = strings.TrimLeft(digits, "0")
	if digits == "" {
		return 0, true
	}
	if negative {
		return 0, false
	}
	coefficient := strings.TrimRight(digits, "0")
	if coefficient != "1" {
		return 0, false
	}
	neededExponent := fractionDigits - (len(digits) - len(coefficient))
	return 1, normalizedExponent(exponent) == strconv.Itoa(neededExponent)
}

func normalizedExponent(exponent string) string {
	negative := strings.HasPrefix(exponent, "-")
	exponent = strings.TrimLeft(strings.TrimPrefix(strings.TrimPrefix(exponent, "+"), "-"), "0")
	if exponent == "" {
		return "0"
	}
	if negative {
		return "-" + exponent
	}
	return exponent
}

// encoding/json replaces unpaired surrogate escapes with U+FFFD. Inspect only
// the raw string escapes first, leaving JSON grammar to the token decoder.
func validStringEscapes(body []byte) bool {
	quoted := false
	for i := 0; i < len(body); i++ {
		if body[i] == '"' {
			quoted = !quoted
			continue
		}
		if !quoted || body[i] != '\\' {
			continue
		}
		i++
		if i >= len(body) {
			return false
		}
		if body[i] != 'u' {
			continue
		}
		value, ok := hexCodeUnit(body, i+1)
		if !ok {
			return false
		}
		i += 4
		if value >= 0xd800 && value <= 0xdbff {
			if i+6 >= len(body) || body[i+1] != '\\' || body[i+2] != 'u' {
				return false
			}
			low, ok := hexCodeUnit(body, i+3)
			if !ok || low < 0xdc00 || low > 0xdfff {
				return false
			}
			i += 6
		} else if value >= 0xdc00 && value <= 0xdfff {
			return false
		}
	}
	return !quoted
}

func hexCodeUnit(body []byte, offset int) (uint16, bool) {
	if offset+4 > len(body) {
		return 0, false
	}
	var value uint16
	for _, b := range body[offset : offset+4] {
		var digit byte
		switch {
		case b >= '0' && b <= '9':
			digit = b - '0'
		case b >= 'a' && b <= 'f':
			digit = b - 'a' + 10
		case b >= 'A' && b <= 'F':
			digit = b - 'A' + 10
		default:
			return 0, false
		}
		value = value*16 + uint16(digit)
	}
	return value, true
}
