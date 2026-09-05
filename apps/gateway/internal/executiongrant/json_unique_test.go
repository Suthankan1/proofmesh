package executiongrant

import (
	"errors"
	"fmt"
	"strings"
	"testing"
)

func TestValidateUniqueJSONMembers_ValidCases(t *testing.T) {
	tests := []struct {
		name              string
		jsonStr           string
		requireObjectRoot bool
	}{
		{
			name:              "empty_object",
			jsonStr:           `{}`,
			requireObjectRoot: true,
		},
		{
			name:              "simple_object",
			jsonStr:           `{"a": 1, "b": "test", "c": true, "d": null}`,
			requireObjectRoot: true,
		},
		{
			name:              "whitespace_padding",
			jsonStr:           "  \n\t  {\n  \"a\": 1\n  }  \r\n\t ",
			requireObjectRoot: true,
		},
		{
			name:              "nested_distinct_keys",
			jsonStr:           `{"obj1": {"key": 1}, "obj2": {"key": 2}}`,
			requireObjectRoot: true,
		},
		{
			name:              "array_with_distinct_objects",
			jsonStr:           `{"items": [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}]}`,
			requireObjectRoot: true,
		},
		{
			name:              "array_root_allowed_when_not_required",
			jsonStr:           `[{"id": 1}, {"id": 2}]`,
			requireObjectRoot: false,
		},
		{
			name:              "primitive_root_allowed_when_not_required",
			jsonStr:           `"hello"`,
			requireObjectRoot: false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if err := validateUniqueJSONMembers([]byte(tc.jsonStr), tc.requireObjectRoot); err != nil {
				t.Fatalf("expected valid JSON, got error: %v", err)
			}
		})
	}
}

func TestValidateUniqueJSONMembers_DuplicateDetection(t *testing.T) {
	tests := []struct {
		name              string
		jsonStr           string
		requireObjectRoot bool
	}{
		{
			name:              "duplicate_toplevel_keys",
			jsonStr:           `{"a": 1, "b": 2, "a": 3}`,
			requireObjectRoot: true,
		},
		{
			name:              "duplicate_nested_keys",
			jsonStr:           `{"outer": {"inner": 1, "inner": 2}}`,
			requireObjectRoot: true,
		},
		{
			name:              "duplicate_in_array_object",
			jsonStr:           `{"items": [{"key": 1, "key": 2}]}`,
			requireObjectRoot: true,
		},
		{
			name:              "duplicate_in_root_array_object",
			jsonStr:           `[{"key": 1, "key": 2}]`,
			requireObjectRoot: false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := validateUniqueJSONMembers([]byte(tc.jsonStr), tc.requireObjectRoot)
			if err == nil {
				t.Fatalf("expected duplicate key error, got nil")
			}
			if !errors.Is(err, errDuplicateKey) {
				t.Fatalf("expected errDuplicateKey, got: %v", err)
			}
		})
	}
}

func TestValidateUniqueJSONMembers_MalformedAndEdgeCases(t *testing.T) {
	tests := []struct {
		name              string
		jsonStr           string
		requireObjectRoot bool
		wantErr           error
	}{
		{
			name:              "empty_string",
			jsonStr:           "",
			requireObjectRoot: true,
			wantErr:           errMalformedJSON,
		},
		{
			name:              "whitespace_only",
			jsonStr:           "   \n\t  ",
			requireObjectRoot: true,
			wantErr:           errMalformedJSON,
		},
		{
			name:              "non_object_root_when_required",
			jsonStr:           `["item1", "item2"]`,
			requireObjectRoot: true,
			wantErr:           errRootNotObject,
		},
		{
			name:              "scalar_root_when_required",
			jsonStr:           `"some string"`,
			requireObjectRoot: true,
			wantErr:           errRootNotObject,
		},
		{
			name:              "trailing_comma_object",
			jsonStr:           `{"a": 1,}`,
			requireObjectRoot: true,
			wantErr:           errMalformedJSON,
		},
		{
			name:              "missing_value",
			jsonStr:           `{"a": }`,
			requireObjectRoot: true,
			wantErr:           errMalformedJSON,
		},
		{
			name:              "missing_comma",
			jsonStr:           `{"a": 1 "b": 2}`,
			requireObjectRoot: true,
			wantErr:           errMalformedJSON,
		},
		{
			name:              "unquoted_key",
			jsonStr:           `{a: 1}`,
			requireObjectRoot: true,
			wantErr:           errMalformedJSON,
		},
		{
			name:              "trailing_content_text",
			jsonStr:           `{"a": 1} trailing content`,
			requireObjectRoot: true,
			wantErr:           errTrailingContent,
		},
		{
			name:              "trailing_content_second_json",
			jsonStr:           `{"a": 1} {"b": 2}`,
			requireObjectRoot: true,
			wantErr:           errTrailingContent,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := validateUniqueJSONMembers([]byte(tc.jsonStr), tc.requireObjectRoot)
			if err == nil {
				t.Fatalf("expected error %v, got nil", tc.wantErr)
			}
			if !errors.Is(err, tc.wantErr) {
				t.Fatalf("expected error %v, got: %v", tc.wantErr, err)
			}
		})
	}
}

func TestValidateUniqueJSONMembers_ErrorRedaction(t *testing.T) {
	const canaryKey = "SUPER_SECRET_DUPLICATE_KEY_DO_NOT_LEAK"
	const canaryVal = "SUPER_SECRET_DUPLICATE_VAL_DO_NOT_LEAK"

	badJSON := fmt.Sprintf(`{"%s": "%s", "%s": "other"}`, canaryKey, canaryVal, canaryKey)

	err := validateUniqueJSONMembers([]byte(badJSON), true)
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	for _, str := range []string{
		err.Error(),
		fmt.Sprintf("%v", err),
		fmt.Sprintf("%+v", err),
	} {
		if strings.Contains(str, canaryKey) {
			t.Errorf("error leaked canary key: %s", str)
		}
		if strings.Contains(str, canaryVal) {
			t.Errorf("error leaked canary value: %s", str)
		}
	}
}
