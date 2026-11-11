package main

import (
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

// Every fixture names the virtual path it plays and the rules it must trigger (empty = must be clean).
func TestFixtures(t *testing.T) {
	ents, err := os.ReadDir("testdata")
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, e := range ents {
		if !strings.HasSuffix(e.Name(), ".txt") {
			continue
		}
		n++
		src, err := os.ReadFile(filepath.Join("testdata", e.Name()))
		if err != nil {
			t.Fatal(err)
		}
		lines := strings.SplitN(string(src), "\n", 3)
		path := strings.TrimPrefix(lines[0], "// path: ")
		want := strings.Fields(strings.TrimPrefix(lines[1], "// want:"))
		var got []string
		for _, v := range Lint(map[string][]byte{path: src}) {
			got = append(got, v.Rule)
		}
		sort.Strings(got)
		sort.Strings(want)
		if len(want) == 0 {
			want = nil
		}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("%s: rules %v, want %v", e.Name(), got, want)
		}
	}
	if n < 20 {
		t.Fatalf("only %d fixtures", n)
	}
}

// The real module is clean: this is the same check the CI step runs.
func TestRealTreeIsClean(t *testing.T) {
	files, err := readTree("../..")
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := files["internal/crp/canon.go"]; !ok {
		t.Fatal("module tree not found")
	}
	for _, v := range Lint(files) {
		t.Error(v)
	}
}
