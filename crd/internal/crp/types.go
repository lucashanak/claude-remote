// Package crp is the CRP/1 codec: frame layout (spec §3.1), the canonical header writer, the receiver rules of
// §3.2, the message types of §3.3 and the error codes of §3.5. It has no I/O of its own beyond io.Reader framing.
//
// Headers are written by the canonical writer in canon.go only. encoding/json is used for parsing (Decoder with
// UseNumber); its Marshal/Encoder escape '<', '>', '&' and U+2028 and MUST NOT write a header (spec §3.1). The
// crdlint gate fails CI on any json.Marshal, json.MarshalIndent or json.NewEncoder in this package.
package crp

// Wire constants (spec §2.2, §2.6, §3.1).
const (
	Magic             = "\x89CRP\r\n\x1a\n"
	JunkLimit         = 65536    // bytes a client skips before the magic (§2.2)
	DefaultMaxFrame   = 1 << 20  // value of the length field (§2.6)
	MinMaxFrame       = 65536    // HELLO below this is refused (§2.6)
	HardMaxFrame      = 16 << 20 // §2.6
	PreNegotiationMax = 65536    // first frame of each direction (§3.2)
	MaxWelcome        = 32768    // WELCOME MUST be <= 32 KiB (§2.3)
	Piece             = 49152    // max blob bytes of one TX/TOUT/RPCPART/RPCRES (§2.6)
	FlagBlob          = 0x01
	maxSafeInt        = 1 << 53 // integers within 53 bits (§3.1); gen.py accepts |v| <= 2^53
)

// Type is the frame type byte.
type Type byte

// CRP/1 core types (spec §3.3).
const (
	HELLO   Type = 0x01
	WELCOME Type = 0x02
	BYE     Type = 0x03
	PING    Type = 0x04
	PONG    Type = 0x05
	ERR     Type = 0x06
	STATE   Type = 0x07
	SUB     Type = 0x10
	UNSUB   Type = 0x11
	ACK     Type = 0x12
	EV      Type = 0x20
	SNAP    Type = 0x21
	TX      Type = 0x22
	TOUT    Type = 0x30
	TSTATE  Type = 0x31
	TSIZE   Type = 0x32
	IN      Type = 0x40
	INACK   Type = 0x41
	INSKIP  Type = 0x42
	RPC     Type = 0x50
	RPCRES  Type = 0x51
	RPCPART Type = 0x52
)

// Dir is one direction of a connection.
type Dir int

const (
	C2S Dir = iota // client -> daemon
	S2C            // daemon -> client
)

func (d Dir) String() string {
	if d == C2S {
		return "c2s"
	}
	return "s2c"
}

// ParseDir maps "c2s"/"s2c" to a Dir.
func ParseDir(s string) (Dir, bool) {
	switch s {
	case "c2s":
		return C2S, true
	case "s2c":
		return S2C, true
	}
	return 0, false
}

type typeInfo struct {
	name     string
	c2s, s2c bool
	blob     bool     // a blob is allowed (§3.3 blob column is not "—")
	required []string // required header keys (§3.3 bold)
}

var types = map[Type]typeInfo{
	HELLO:   {"HELLO", true, false, false, []string{"proto", "dev", "link", "att", "ie", "app", "feats", "maxFrame"}},
	WELCOME: {"WELCOME", false, true, false, []string{"proto", "conn", "srv", "feats", "maxFrame", "now", "host", "streams", "inGen"}},
	BYE:     {"BYE", true, true, false, []string{"code"}},
	PING:    {"PING", true, true, false, []string{"t"}},
	PONG:    {"PONG", true, true, false, []string{"t"}},
	ERR:     {"ERR", false, true, false, []string{"code", "re"}},
	STATE:   {"STATE", true, false, false, []string{"fg"}},
	SUB:     {"SUB", true, false, false, []string{"st"}},
	UNSUB:   {"UNSUB", true, false, false, []string{"st"}},
	ACK:     {"ACK", true, false, false, []string{"acks"}},
	EV:      {"EV", false, true, true, []string{"st", "ep", "seq"}},
	SNAP:    {"SNAP", false, true, false, []string{"st", "ep", "seq", "from", "reason"}},
	TX:      {"TX", false, true, true, []string{"wid", "u", "f", "o", "enc"}},
	TOUT:    {"TOUT", false, true, true, []string{"sid", "tseq"}},
	TSTATE:  {"TSTATE", false, true, false, []string{"sid", "state"}},
	TSIZE:   {"TSIZE", true, false, false, []string{"sid", "cols", "rows"}},
	IN:      {"IN", true, false, true, []string{"sid", "iseq", "exp"}},
	INACK:   {"INACK", false, true, false, []string{"sid", "iseq", "st"}},
	INSKIP:  {"INSKIP", true, false, false, []string{"sid", "to"}},
	RPC:     {"RPC", true, false, true, []string{"id", "m", "tag", "cmd", "ms"}},
	RPCRES:  {"RPCRES", false, true, true, []string{"id", "ok"}},
	RPCPART: {"RPCPART", false, true, true, []string{"id", "part"}},
}

// Name returns the type name ("" for unknown types).
func (t Type) Name() string { return types[t].name }

// Known reports whether t is a CRP/1 core type.
func (t Type) Known() bool { _, ok := types[t]; return ok }

// TypeByName maps a name like "WELCOME" to its code.
func TypeByName(name string) (Type, bool) {
	for t, ti := range types {
		if ti.name == name {
			return t, true
		}
	}
	return 0, false
}

// Typed top-level header keys (spec §3.2, gen.py INT_FIELDS/STR_FIELDS/BOOL_FIELDS).
var intFields = set("hi", "at", "seq", "iseq", "tseq", "f", "o", "t", "maxFrame", "cols", "rows", "part", "att", "id", "ms",
	"to", "from", "now", "pingMs", "retryMs", "min", "max", "exp")
var strFields = set("st", "ep", "sid", "dev", "link", "ie", "code", "re", "tag", "cmd", "m", "wid", "u", "enc", "reason",
	"state", "app", "srv", "conn", "inGen", "owner", "via", "orig", "rule", "msg")
var boolFields = set("fg", "ok", "last", "more", "reset", "redraw", "active")

func set(xs ...string) map[string]bool {
	m := make(map[string]bool, len(xs))
	for _, x := range xs {
		m[x] = true
	}
	return m
}

// Error codes (spec §3.5).
const (
	EFrame       = "E_FRAME"
	EProto       = "E_PROTO"
	EVersion     = "E_VERSION"
	ENoDaemon    = "E_NO_DAEMON"
	EDaemonDown  = "E_DAEMON_DOWN"
	EOff         = "E_OFF"
	Superseded   = "SUPERSEDED"
	Stale        = "STALE"
	EShutdown    = "E_SHUTDOWN"
	ETmuxSocket  = "E_TMUX_SOCKET"
	ENotActive   = "E_NOT_ACTIVE"
	ENoSession   = "E_NO_SESSION"
	EInUnsupport = "E_IN_UNSUPPORTED"
)

// ProtoError is a receiver-rule violation; Code is E_FRAME, E_PROTO or E_NO_DAEMON.
type ProtoError struct {
	Code string
	Msg  string
}

func (e *ProtoError) Error() string { return e.Code + ": " + e.Msg }

func perr(code, msg string) *ProtoError { return &ProtoError{Code: code, Msg: msg} }
