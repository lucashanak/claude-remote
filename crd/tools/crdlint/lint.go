// Command crdlint is the CI gate over the crd Go sources (spec §6.5, §3.1, §8.4.3; dodatek C5, D-H2, D-H5, D-H10,
// D-H22, review A-F14). It works on the syntax tree, not on grep: a function is caught where it is named (called or
// taken as a value), sums of string literals are checked as the string they make, os/exec may only be imported by
// the process starters. Not caught: a call name built from named constants or at run time (review keeps that).
//
//	crdlint [module-dir]    exit 1 and one line per violation
//
// The rule tables below are the deny list itself; this directory is not scanned and is never imported by crd.
package main

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Violation is one finding.
type Violation struct {
	File string
	Line int
	Rule string
	Msg  string
}

func (v Violation) String() string {
	return fmt.Sprintf("%s:%d: %s: %s", v.File, v.Line, v.Rule, v.Msg)
}

const (
	ruleJSON      = "codec-json"     // encoding/json writer in the codec package
	ruleTmuxLit   = "tmux-literal"   // "tmux" or ".../tmux" outside the gate
	ruleTmuxVerb  = "tmux-verb"      // a creating/killing/global verb or -A/-g outside the deny list
	ruleTmuxN     = "tmux-no-N"      // the gate function lost its -N
	ruleSignal    = "signal"         // syscall/unix Kill, Tgkill, os.FindProcess, Process.Kill/Signal
	ruleSIGUSR1   = "sigusr1"        // never, anywhere (§8.4.3)
	ruleProcKill  = "pgrep-pkill"    // pgrep/pkill/killall anywhere (D-H5)
	ruleExec      = "exec"           // process start outside the allowed functions
	ruleFSLink    = "fs-link-remove" // os.Symlink, os.Link, os.Remove, os.RemoveAll outside the helpers (D-H10)
	ruleTestMain  = "testmain"       // a test package that imports testenv has a TestMain calling testenv.Main
	ruleTestenvIn = "testenv-import" // only _test.go files import testenv (D-H22)
	ruleExecImp   = "exec-import"    // os/exec imported outside the files that may start processes (review A-F14)
	ruleRawSys    = "raw-syscall"    // syscall.Syscall*/RawSyscall* anywhere (a kill or unlink by number, A-F14)
	ruleMust      = "must-encode"    // crp.MustEncode/MustCanon outside tests: the send path never panics (P1.1)
)

// execImporters may import os/exec; any other file that does is a violation, whatever it calls (review A-F14:
// `run := exec.Command` or `&exec.Cmd{…}` start processes without a call named Command).
var execImporters = map[string]bool{
	"internal/tmuxgate/gate.go": true, "internal/client/probe.go": true, "internal/itest/main_test.go": true,
	"internal/testenv/testenv.go": true, "internal/testenv/testenv_test.go": true,
}

// pkgFuncs are package-level functions that are violations wherever they are named (called or taken as a value).
var pkgFuncs = map[[2]string]struct{ rule, msg string }{
	{"syscall", "Kill"}: {ruleSignal, "syscall.Kill"}, {"syscall", "Tgkill"}: {ruleSignal, "syscall.Tgkill"},
	{"syscall", "Tkill"}: {ruleSignal, "syscall.Tkill"}, {"golang.org/x/sys/unix", "Kill"}: {ruleSignal, "unix.Kill"},
	{"golang.org/x/sys/unix", "Tgkill"}: {ruleSignal, "unix.Tgkill"}, {"golang.org/x/sys/unix", "Tkill"}: {ruleSignal, "unix.Tkill"},
	{"os", "FindProcess"}:         {ruleSignal, "os.FindProcess"},
	{"os/exec", "Command"}:        {ruleExec, "exec.Command outside the allowed starters"},
	{"os/exec", "CommandContext"}: {ruleExec, "exec.CommandContext outside the allowed starters"},
	{"os", "StartProcess"}:        {ruleExec, "os.StartProcess"},
	{"syscall", "ForkExec"}:       {ruleExec, "syscall.ForkExec"}, {"syscall", "Exec"}: {ruleExec, "syscall.Exec"},
	{"syscall", "StartProcess"}: {ruleExec, "syscall.StartProcess"},
	{"os", "Symlink"}:           {ruleFSLink, "os.Symlink outside the safe helpers"},
	{"os", "Link"}:              {ruleFSLink, "os.Link outside the safe helpers"},
	{"os", "Remove"}:            {ruleFSLink, "os.Remove outside the safe helpers"},
	{"os", "RemoveAll"}:         {ruleFSLink, "os.RemoveAll outside the safe helpers"},
	{"syscall", "Unlink"}:       {ruleFSLink, "syscall.Unlink"}, {"syscall", "Unlinkat"}: {ruleFSLink, "syscall.Unlinkat"},
	{"syscall", "Rmdir"}: {ruleFSLink, "syscall.Rmdir"}, {"syscall", "Symlink"}: {ruleFSLink, "syscall.Symlink"},
	{"syscall", "Link"}:    {ruleFSLink, "syscall.Link"},
	{"syscall", "Syscall"}: {ruleRawSys, "syscall.Syscall"}, {"syscall", "Syscall6"}: {ruleRawSys, "syscall.Syscall6"},
	{"syscall", "RawSyscall"}: {ruleRawSys, "syscall.RawSyscall"}, {"syscall", "RawSyscall6"}: {ruleRawSys, "syscall.RawSyscall6"},
}

const crpPath = "github.com/lucashanak/claude-remote/crd/internal/crp"

// constString folds a string literal or a sum of string literals ("kill-"+"server"); ok=false for anything else.
func constString(e ast.Expr) (string, bool) {
	switch x := e.(type) {
	case *ast.BasicLit:
		if x.Kind != token.STRING {
			return "", false
		}
		s, err := strconv.Unquote(x.Value)
		return s, err == nil
	case *ast.ParenExpr:
		return constString(x.X)
	case *ast.BinaryExpr:
		if x.Op != token.ADD {
			return "", false
		}
		a, ok1 := constString(x.X)
		b, ok2 := constString(x.Y)
		return a + b, ok1 && ok2
	}
	return "", false
}

const testenvPath = "github.com/lucashanak/claude-remote/crd/internal/testenv"

// allow maps "dir/file.go:Func" (Func = function or method name, "var:<name>" for a top-level var) to the rules
// that may fire there. Everything not listed gets every rule.
var allow = map[string][]string{
	"internal/tmuxgate/gate.go:binary":     {ruleTmuxLit},
	"internal/tmuxgate/gate.go:var:denied": {ruleTmuxVerb},
	// the gate's own negative table: these words must reach Check to be refused there
	"internal/tmuxgate/gate_test.go:TestCheckAllowsOnlySpecShapes":     {ruleTmuxVerb},
	"internal/tmuxgate/gate_test.go:TestRunRefusesAPathOutsideTheRoot": {ruleTmuxVerb},
	"internal/tmuxgate/gate.go:Run":                                    {ruleExec},
	"internal/tmuxgate/gate.go:checkVersion":                           {ruleExec},
	// the gate's own tests put a fake binary named tmux first on PATH (timeout, version)
	"internal/tmuxgate/gate_test.go:fakeTmux":   {ruleTmuxLit},
	"internal/client/probe.go:startShim":        {ruleExec},
	"internal/daemon/listen.go:listen":          {ruleFSLink},
	"internal/cli/selftest.go:runSelftest":      {ruleFSLink},
	"internal/stream/rotate.go:rotate":          {ruleFSLink},
	"internal/stream/rotate.go:cleanRotTemps":   {ruleFSLink},
	"internal/daemon/log.go:cut":                {ruleFSLink},
	"internal/testenv/testenv.go:Command":       {ruleExec},
	"internal/testenv/testenv.go:Tmux":          {ruleTmuxLit},
	"internal/testenv/testenv.go:Signal":        {ruleSignal},
	"internal/testenv/testenv.go:sweep":         {ruleSignal},
	"internal/testenv/testenv.go:RequireTmux":   {ruleTmuxLit},
	"internal/testenv/testenv.go:RecordingTmux": {ruleTmuxLit},
	"internal/testenv/testenv.go:prepare":       {ruleTmuxLit},
	"internal/testenv/testenv.go:findRepoRoot":  {ruleTmuxLit},
	// the harness's own check that the shim refuses the live socket (exit 97, no connection)
	"internal/testenv/testenv_test.go:TestTmuxIsTheShimAndRefusesTheLiveSocket": {ruleTmuxLit},
	"internal/testenv/testenv.go:StartTmux":                                     {ruleTmuxVerb},
	"internal/testenv/testenv.go:NewTmuxSession":                                {ruleTmuxVerb},
	"internal/testenv/testenv.go:NewTmuxSessionArgv0":                           {ruleTmuxVerb},
	"internal/testenv/testenv.go:KillTmuxSession":                               {ruleTmuxVerb},
	"internal/testenv/testenv.go:KillTmux":                                      {ruleTmuxVerb},
	"internal/testenv/testenv.go:SafeRemoveAll":                                 {ruleFSLink},
	"internal/testenv/testenv.go:SafeSymlink":                                   {ruleFSLink},
	// the sess tests put a fake tmux that prints a fixed listing first on PATH (forged records, long names)
	"internal/stream/round3_test.go:fakeTmuxListing": {ruleTmuxLit},
}

var tmuxVerbs = []string{"new-session", "start-server", "kill-server", "kill-session", "set-option", "resize-window", "window-size"}

// Lint checks a module given as relative path -> source.
func Lint(files map[string][]byte) []Violation {
	var out []Violation
	fset := token.NewFileSet()
	type pkgInfo struct {
		testImportsTestenv bool
		hasTestMain        bool
		firstTest          string
	}
	pkgs := map[string]*pkgInfo{}
	names := make([]string, 0, len(files))
	for n := range files {
		names = append(names, n)
	}
	sort.Strings(names)
	gateHasN := false
	for _, name := range names {
		if !strings.HasSuffix(name, ".go") {
			continue
		}
		f, err := parser.ParseFile(fset, name, files[name], parser.SkipObjectResolution)
		if err != nil {
			out = append(out, Violation{name, 0, "parse", err.Error()})
			continue
		}
		dir := filepath.Dir(name)
		isTest := strings.HasSuffix(name, "_test.go")
		pi := pkgs[dir]
		if pi == nil {
			pi = &pkgInfo{}
			pkgs[dir] = pi
		}
		imports := map[string]string{} // local name -> path
		for _, im := range f.Imports {
			p, _ := strconv.Unquote(im.Path.Value)
			local := filepath.Base(p)
			if im.Name != nil {
				local = im.Name.Name
			}
			imports[local] = p
			if p == "os/exec" && !execImporters[name] {
				out = append(out, Violation{name, fset.Position(im.Pos()).Line, ruleExecImp, "os/exec imported outside the process starters"})
			}
			if p == testenvPath {
				if isTest {
					pi.testImportsTestenv = true
				} else if dir != "internal/testenv" {
					out = append(out, Violation{name, fset.Position(im.Pos()).Line, ruleTestenvIn, "non-test file imports testenv"})
				}
			}
		}
		if isTest && pi.firstTest == "" {
			pi.firstTest = name
		}
		report := func(scope string, pos token.Pos, rule, msg string) {
			for _, r := range allow[name+":"+scope] {
				if r == rule {
					return
				}
			}
			out = append(out, Violation{name, fset.Position(pos).Line, rule, msg})
		}
		checkString := func(scope string, s string, pos token.Pos) {
			if s == "tmux" || strings.HasSuffix(s, "/tmux") {
				report(scope, pos, ruleTmuxLit, "tmux binary literal outside the gate")
			}
			if s == "-A" || s == "-g" {
				report(scope, pos, ruleTmuxVerb, "tmux flag "+s+" outside the deny list")
			}
			for _, w := range strings.FieldsFunc(s, func(r rune) bool { return r == ' ' || r == '\t' || r == '\n' || r == '"' || r == '\'' || r == ';' }) {
				for _, v := range tmuxVerbs {
					if w == v {
						report(scope, pos, ruleTmuxVerb, "tmux verb "+v+" outside the deny list")
					}
				}
				switch w {
				case "pgrep", "pkill", "killall":
					report(scope, pos, ruleProcKill, w+" is forbidden (identity before signal, D-H5)")
				}
			}
			if scope == "Run" && name == "internal/tmuxgate/gate.go" && s == "-N" {
				gateHasN = true
			}
		}
		visit := func(scope string, n ast.Node) {
			ast.Inspect(n, func(n ast.Node) bool {
				switch x := n.(type) {
				case *ast.BinaryExpr:
					// a sum of literals is checked as the string it makes (review A-F14: "kill-"+"server")
					if s, ok := constString(x); ok {
						checkString(scope, s, x.Pos())
						return false
					}
				case *ast.BasicLit:
					if s, ok := constString(x); ok {
						checkString(scope, s, x.Pos())
					}
				case *ast.CompositeLit:
					if sel, ok := x.Type.(*ast.SelectorExpr); ok && sel.Sel.Name == "Cmd" {
						if id, ok := sel.X.(*ast.Ident); ok && imports[id.Name] == "os/exec" {
							report(scope, x.Pos(), ruleExec, "exec.Cmd literal outside the allowed starters")
						}
					}
				case *ast.SelectorExpr:
					if x.Sel.Name == "SIGUSR1" {
						report(scope, x.Pos(), ruleSIGUSR1, "SIGUSR1 is never sent by crd")
					}
					// named, not only called: `run := exec.Command` is the same start (review A-F14)
					if id, ok := x.X.(*ast.Ident); ok {
						if r, bad := pkgFuncs[[2]string{imports[id.Name], x.Sel.Name}]; bad {
							report(scope, x.Pos(), r.rule, r.msg)
						}
						if imports[id.Name] == crpPath && !isTest && (x.Sel.Name == "MustEncode" || x.Sel.Name == "MustCanon") {
							report(scope, x.Pos(), ruleMust, "crp."+x.Sel.Name+" outside tests (the send path never panics)")
						}
					}
				case *ast.CallExpr:
					if id, ok := x.Fun.(*ast.Ident); ok {
						if dir == "internal/crp" && !isTest && (id.Name == "MustEncode" || id.Name == "MustCanon") {
							report(scope, x.Pos(), ruleMust, id.Name+" called in the codec")
						}
						if id.Name == "new" && len(x.Args) == 1 {
							if sel, ok := x.Args[0].(*ast.SelectorExpr); ok && sel.Sel.Name == "Cmd" {
								if pid, ok := sel.X.(*ast.Ident); ok && imports[pid.Name] == "os/exec" {
									report(scope, x.Pos(), ruleExec, "new(exec.Cmd) outside the allowed starters")
								}
							}
						}
					}
					sel, ok := x.Fun.(*ast.SelectorExpr)
					if !ok {
						return true
					}
					pkg := ""
					if id, ok := sel.X.(*ast.Ident); ok {
						pkg = imports[id.Name]
					}
					fn := sel.Sel.Name
					switch {
					case pkg == "encoding/json" && (fn == "Marshal" || fn == "MarshalIndent" || fn == "NewEncoder"):
						if dir == "internal/crp" {
							report(scope, x.Pos(), ruleJSON, "json."+fn+" in the codec package (canonical writer only)")
						}
					case pkg == "" && (fn == "Kill" || fn == "Signal") && isProcessExpr(sel.X):
						report(scope, x.Pos(), ruleSignal, "(*os.Process)."+fn)
					}
				}
				return true
			})
		}
		for _, d := range f.Decls {
			switch x := d.(type) {
			case *ast.FuncDecl:
				if isTest && x.Name.Name == "TestMain" && x.Recv == nil && callsTestenvMain(x, imports, dir) {
					pi.hasTestMain = true
				}
				visit(x.Name.Name, x)
			case *ast.GenDecl:
				for _, sp := range x.Specs {
					scope := "decl"
					if vs, ok := sp.(*ast.ValueSpec); ok && len(vs.Names) > 0 {
						scope = "var:" + vs.Names[0].Name
					}
					visit(scope, sp)
				}
			}
		}
	}
	if _, ok := files["internal/tmuxgate/gate.go"]; ok && !gateHasN {
		out = append(out, Violation{"internal/tmuxgate/gate.go", 0, ruleTmuxN, "gate function Run has no \"-N\""})
	}
	dirs := make([]string, 0, len(pkgs))
	for d := range pkgs {
		dirs = append(dirs, d)
	}
	sort.Strings(dirs)
	for _, d := range dirs {
		pi := pkgs[d]
		if pi.testImportsTestenv && !pi.hasTestMain {
			out = append(out, Violation{pi.firstTest, 0, ruleTestMain, "package " + d + " tests import testenv but have no TestMain calling testenv.Main"})
		}
	}
	return out
}

func isProcessExpr(e ast.Expr) bool {
	switch x := e.(type) {
	case *ast.SelectorExpr:
		return x.Sel.Name == "Process"
	case *ast.Ident:
		return x.Name == "proc" || x.Name == "p" || x.Name == "process"
	}
	return false
}

func callsTestenvMain(fd *ast.FuncDecl, imports map[string]string, dir string) bool {
	found := false
	ast.Inspect(fd, func(n ast.Node) bool {
		c, ok := n.(*ast.CallExpr)
		if !ok {
			return true
		}
		switch f := c.Fun.(type) {
		case *ast.SelectorExpr:
			if id, ok := f.X.(*ast.Ident); ok && imports[id.Name] == testenvPath && f.Sel.Name == "Main" {
				found = true
			}
		case *ast.Ident:
			if dir == "internal/testenv" && f.Name == "Main" {
				found = true
			}
		}
		return true
	})
	return found
}

// readTree loads every .go file of the module except this tool and testdata directories.
func readTree(root string) (map[string][]byte, error) {
	files := map[string][]byte{}
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(root, p)
		if d.IsDir() {
			if d.Name() == "testdata" || rel == "tools/crdlint" {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasSuffix(p, ".go") {
			b, err := os.ReadFile(p)
			if err != nil {
				return err
			}
			files[rel] = b
		}
		return nil
	})
	return files, err
}

func main() {
	root := "."
	if len(os.Args) > 1 {
		root = os.Args[1]
	}
	files, err := readTree(root)
	if err != nil {
		fmt.Fprintln(os.Stderr, "crdlint:", err)
		os.Exit(2)
	}
	vs := Lint(files)
	for _, v := range vs {
		fmt.Println(v)
	}
	if len(vs) > 0 {
		os.Exit(1)
	}
	fmt.Printf("crdlint: %d files clean\n", len(files))
}
