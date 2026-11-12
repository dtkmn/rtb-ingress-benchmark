package models

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestExactNumericLMT(t *testing.T) {
	for _, token := range []string{"0", "-0", "0.000", "-0e999999999999999999", "1", "1.0", "1e0", "10e-1", "100e-2", "0.001e3", "1E+000", "1e-000", "1e" + strings.Repeat("0", 10000)} {
		t.Run(token[:min(len(token), 30)], func(t *testing.T) {
			request, err := DecodeBidRequest([]byte(`{"id":"x","site":{},"device":{"lmt":` + token + `}}`))
			if err != nil {
				t.Fatalf("valid exact number %q rejected: %v", token[:min(len(token), 30)], err)
			}
			want := 1
			if strings.HasPrefix(token, "-0") || token == "0" || token == "0.000" {
				want = 0
			}
			if request.Device.LMT != want {
				t.Fatalf("lmt=%d want%d", request.Device.LMT, want)
			}
		})
	}
	for _, token := range []string{"1.00000000000000001", "0.99999999999999999", "1e999999999999999999", "1e-999999999999999999", "-1", "0.1", "2", "true", "false", `"1"`, "null", "01", "+1", "1."} {
		t.Run("reject_"+token, func(t *testing.T) {
			if _, err := DecodeBidRequest([]byte(`{"id":"x","site":{},"device":{"lmt":` + token + `}}`)); err == nil {
				t.Fatal("invalid lmt accepted")
			}
		})
	}
}

func TestStrictRawJSONAndClosedMembers(t *testing.T) {
	bad := []string{
		`{"id":"x","ID":"y","site":{},"device":{}}`,
		`{"id":"x","site":{"domain":"a","\u0064omain":"b"},"device":{}}`,
		`{"id":"x","site":{},"device":{"ip":"a","\u0069p":"b"}}`,
		`{"id":"x","app":{"id":"a","id":"b"},"device":{}}`,
		`{"id":"x","site":{},"device":{},"user":{"id":"a","id":"b"}}`,
		`{"id":"x","site":{},"device":{},"app":{"other":null}}`,
		`{"id":"x","site":{},"device":{},"user":[]}`,
		`{"id":"x","site":{},"device":{"ua":true}}`,
		`{"id":"x","site":{},"device":{}} true`,
		`{"id":"x","site":{},"device":{}}x`,
		`{"id":"\ud800","site":{},"device":{}}`,
		`{"id":"\udc00","site":{},"device":{}}`,
		`{"id":"x","site":{"id":"\ud800\\udc00"},"device":{}}`,
		`{"id":"x","site":{},"device":{"\ud800":0}}`,
		`{"id":"x","site":{},"device":{"lmt":1,"ua":false}}`,
	}
	for _, body := range bad {
		t.Run(body, func(t *testing.T) {
			if _, err := DecodeBidRequest([]byte(body)); err == nil {
				t.Fatal("invalid input accepted")
			}
		})
	}
	for _, id := range []string{`\ud83d\ude00`, `\\ud800`, `\u00a0`, `\u0085`, `\u200b`, ` \tkept\r\n `, `\ufffd`} {
		body := []byte(`{"id":"` + id + `","site":{},"device":{}}` + " \t\r\n")
		request, err := DecodeBidRequest(body)
		if err != nil {
			t.Fatalf("valid unicode/whitespace %q rejected: %v", id, err)
		}
		var original struct {
			ID string `json:"id"`
		}
		if err = json.Unmarshal(body, &original); err != nil {
			t.Fatal(err)
		}
		if request.ID != original.ID {
			t.Fatalf("id changed: %q -> %q", original.ID, request.ID)
		}
	}
}

func TestUnicodeScalarLimits(t *testing.T) {
	for _, row := range []struct {
		field string
		n     int
		valid bool
	}{{"id", 256, true}, {"id", 257, false}, {"ua", 4096, true}, {"ua", 4097, false}} {
		t.Run(row.field+"_"+strings.Repeat("x", row.n%10), func(t *testing.T) {
			id, ua := "x", ""
			if row.field == "id" {
				id = strings.Repeat("😀", row.n)
			} else {
				ua = strings.Repeat("😀", row.n)
			}
			body, _ := json.Marshal(map[string]any{"id": id, "site": map[string]any{}, "device": map[string]any{"ua": ua}})
			_, err := DecodeBidRequest(body)
			if (err == nil) != row.valid {
				t.Fatalf("%s scalars%d error%v", row.field, row.n, err)
			}
		})
	}
}
