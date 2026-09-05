package executiongrant

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
)

var (
	errDuplicateKey    = errors.New("duplicate json member name")
	errMalformedJSON   = errors.New("malformed json")
	errRootNotObject   = errors.New("json root must be an object")
	errTrailingContent = errors.New("trailing content after json value")
)

// validateUniqueJSONMembers validates that the given JSON data contains exactly one
// valid JSON value and that no object at any nesting level contains duplicate member names.
// If requireObjectRoot is true, the top-level value must be a JSON object ({...}).
// Trailing non-whitespace content is strictly rejected.
// Returned errors are sanitized and do not contain input contents or property names.
func validateUniqueJSONMembers(data []byte, requireObjectRoot bool) error {
	if len(bytes.TrimSpace(data)) == 0 {
		return errMalformedJSON
	}

	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()

	firstTok, err := dec.Token()
	if err != nil {
		return errMalformedJSON
	}

	delim, isDelim := firstTok.(json.Delim)
	if requireObjectRoot {
		if !isDelim || delim != '{' {
			return errRootNotObject
		}
		if err := parseObjectMembers(dec); err != nil {
			return err
		}
	} else {
		if err := parseValue(dec, firstTok); err != nil {
			return err
		}
	}

	// Ensure no trailing tokens / non-whitespace content
	if _, err := dec.Token(); err != io.EOF {
		return errTrailingContent
	}

	return nil
}

func parseValue(dec *json.Decoder, tok json.Token) error {
	delim, ok := tok.(json.Delim)
	if !ok {
		// Primitive value (string, json.Number, bool, nil)
		return nil
	}

	switch delim {
	case '{':
		return parseObjectMembers(dec)
	case '[':
		return parseArrayMembers(dec)
	default:
		// Unexpected closing delimiter as a value ('}' or ']')
		return errMalformedJSON
	}
}

func parseObjectMembers(dec *json.Decoder) error {
	seen := make(map[string]struct{})
	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return errMalformedJSON
		}
		keyStr, ok := keyTok.(string)
		if !ok {
			return errMalformedJSON
		}
		if _, exists := seen[keyStr]; exists {
			return errDuplicateKey
		}
		seen[keyStr] = struct{}{}

		valTok, err := dec.Token()
		if err != nil {
			return errMalformedJSON
		}
		if err := parseValue(dec, valTok); err != nil {
			return err
		}
	}

	endTok, err := dec.Token()
	if err != nil {
		return errMalformedJSON
	}
	delim, ok := endTok.(json.Delim)
	if !ok || delim != '}' {
		return errMalformedJSON
	}
	return nil
}

func parseArrayMembers(dec *json.Decoder) error {
	for dec.More() {
		elemTok, err := dec.Token()
		if err != nil {
			return errMalformedJSON
		}
		if err := parseValue(dec, elemTok); err != nil {
			return err
		}
	}

	endTok, err := dec.Token()
	if err != nil {
		return errMalformedJSON
	}
	delim, ok := endTok.(json.Delim)
	if !ok || delim != ']' {
		return errMalformedJSON
	}
	return nil
}
