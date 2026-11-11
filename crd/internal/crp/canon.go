package crp

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strconv"
	"unicode/utf8"
)

// Obj is a decoded header or nested JSON object. Values are string, int64, bool, nil, []any or Obj.
type Obj = map[string]any

// Canon writes v as canonical JSON (spec §3.1): keys sorted by code point, no insignificant whitespace, UTF-8 raw
// (U+2028/2029, '<', '>', '&' and DEL unescaped), only '"', '\\' and C0 controls escaped (\b \t \n \f \r short,
// others \u00xx lowercase), integers without fraction or exponent within 53 bits.
func Canon(v any) ([]byte, error) {
	var b bytes.Buffer
	if err := writeValue(&b, v); err != nil {
		return nil, err
	}
	return b.Bytes(), nil
}

// MustCanon is Canon for values built by the daemon itself; an error there is a programming bug.
func MustCanon(v any) []byte {
	b, err := Canon(v)
	if err != nil {
		panic(err)
	}
	return b
}

func writeValue(b *bytes.Buffer, v any) error {
	switch x := v.(type) {
	case nil:
		b.WriteString("null")
	case bool:
		if x {
			b.WriteString("true")
		} else {
			b.WriteString("false")
		}
	case string:
		writeString(b, x)
	case int:
		return writeInt(b, int64(x))
	case int64:
		return writeInt(b, x)
	case int32:
		return writeInt(b, int64(x))
	case uint32:
		return writeInt(b, int64(x))
	case json.Number:
		n, err := parseInt(string(x))
		if err != nil {
			return err
		}
		return writeInt(b, n)
	case []any:
		b.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				b.WriteByte(',')
			}
			if err := writeValue(b, e); err != nil {
				return err
			}
		}
		b.WriteByte(']')
	case []string:
		b.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				b.WriteByte(',')
			}
			writeString(b, e)
		}
		b.WriteByte(']')
	case []Obj:
		b.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				b.WriteByte(',')
			}
			if err := writeValue(b, e); err != nil {
				return err
			}
		}
		b.WriteByte(']')
	case map[string]any:
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		// Byte order of UTF-8 equals code point order.
		sort.Strings(keys)
		b.WriteByte('{')
		for i, k := range keys {
			if i > 0 {
				b.WriteByte(',')
			}
			writeString(b, k)
			b.WriteByte(':')
			if err := writeValue(b, x[k]); err != nil {
				return err
			}
		}
		b.WriteByte('}')
	default:
		return fmt.Errorf("canon: unsupported value type %T", v)
	}
	return nil
}

func writeInt(b *bytes.Buffer, n int64) error {
	if n > maxSafeInt || n < -maxSafeInt {
		return fmt.Errorf("canon: integer %d outside 53 bits", n)
	}
	b.WriteString(strconv.FormatInt(n, 10))
	return nil
}

const hexdigits = "0123456789abcdef"

func writeString(b *bytes.Buffer, s string) {
	b.WriteByte('"')
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch c {
		case '"':
			b.WriteString(`\"`)
		case '\\':
			b.WriteString(`\\`)
		case '\b':
			b.WriteString(`\b`)
		case '\t':
			b.WriteString(`\t`)
		case '\n':
			b.WriteString(`\n`)
		case '\f':
			b.WriteString(`\f`)
		case '\r':
			b.WriteString(`\r`)
		default:
			if c < 0x20 {
				b.WriteString(`\u00`)
				b.WriteByte(hexdigits[c>>4])
				b.WriteByte(hexdigits[c&0xF])
			} else {
				b.WriteByte(c)
			}
		}
	}
	b.WriteByte('"')
}

var intRe = regexp.MustCompile(`^-?(0|[1-9][0-9]*)$`)

func parseInt(s string) (int64, error) {
	if !intRe.MatchString(s) {
		return 0, fmt.Errorf("number %q is not an integer without fraction or exponent", s)
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil || n > maxSafeInt || n < -maxSafeInt {
		return 0, fmt.Errorf("integer %s outside 53 bits", s)
	}
	return n, nil
}

var errDupKey = errors.New("duplicate key")

// ParseObject parses a header: valid UTF-8, one JSON object, no duplicate keys, integers only (spec §3.1, §3.2).
func ParseObject(raw []byte) (Obj, error) {
	v, err := ParseValue(raw)
	if err != nil {
		return nil, err
	}
	o, ok := v.(map[string]any)
	if !ok {
		return nil, errors.New("not a JSON object")
	}
	return o, nil
}

// ParseValue parses any JSON value with the same rules as ParseObject.
func ParseValue(raw []byte) (any, error) {
	if !utf8.Valid(raw) {
		return nil, errors.New("not UTF-8")
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	v, err := readValue(dec)
	if err != nil {
		return nil, err
	}
	if _, err := dec.Token(); err != io.EOF {
		return nil, errors.New("trailing data after the JSON value")
	}
	return v, nil
}

func readValue(dec *json.Decoder) (any, error) {
	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}
	switch t := tok.(type) {
	case json.Delim:
		switch t {
		case '{':
			o := Obj{}
			for dec.More() {
				kt, err := dec.Token()
				if err != nil {
					return nil, err
				}
				k, ok := kt.(string)
				if !ok {
					return nil, errors.New("object key is not a string")
				}
				if _, dup := o[k]; dup {
					return nil, fmt.Errorf("%w %q", errDupKey, k)
				}
				v, err := readValue(dec)
				if err != nil {
					return nil, err
				}
				o[k] = v
			}
			if _, err := dec.Token(); err != nil { // '}'
				return nil, err
			}
			return o, nil
		case '[':
			a := []any{}
			for dec.More() {
				v, err := readValue(dec)
				if err != nil {
					return nil, err
				}
				a = append(a, v)
			}
			if _, err := dec.Token(); err != nil { // ']'
				return nil, err
			}
			return a, nil
		}
		return nil, fmt.Errorf("unexpected delimiter %v", t)
	case json.Number:
		return parseInt(string(t))
	case string, bool, nil:
		return t, nil
	}
	return nil, fmt.Errorf("unexpected token %T", tok)
}

// Int reads an integer header value.
func Int(o Obj, k string) (int64, bool) {
	v, ok := o[k].(int64)
	return v, ok
}

// Str reads a string header value.
func Str(o Obj, k string) (string, bool) {
	v, ok := o[k].(string)
	return v, ok
}

// Bool reads a boolean header value.
func Bool(o Obj, k string) (bool, bool) {
	v, ok := o[k].(bool)
	return v, ok
}

// Sub reads a nested object.
func Sub(o Obj, k string) (Obj, bool) {
	v, ok := o[k].(map[string]any)
	return v, ok
}
