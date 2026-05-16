// screens-terminal.jsx — Terminal screen with xterm sim + transcript + tabs + control bar

const { useState: useStateTerm, useEffect: useEffectTerm, useRef: useRefTerm } = React;

// ============================================================
// Fake xterm-like terminal output (for "raw" mode)
// ============================================================
const RAW_TERMINAL_LINES = [
  { c: '#fda5a5', t: '╭───────────────────────────────────────────────────╮' },
  { c: '#fda5a5', t: '│  ' },{ c: '#fcd34d', t: 'Claude Code v2.1.91' }, { c: '#fda5a5', t: '                              │', append: true },
  { c: '#fda5a5', t: '│                                                   │' },
  { c: '#fda5a5', t: '│  ' }, { c: '#e2e8f0', t: 'Welcome back, Lukáš.' }, { c: '#fda5a5', t: '                          │', append: true },
  { c: '#fda5a5', t: '│  ' }, { c: '#94a3b8', t: 'Opus 4.6 (1M context) · Claude Max' }, { c: '#fda5a5', t: '              │', append: true },
  { c: '#fda5a5', t: '│  ' }, { c: '#94a3b8', t: '~/claude-remote' }, { c: '#fda5a5', t: '                                │', append: true },
  { c: '#fda5a5', t: '╰───────────────────────────────────────────────────╯' },
  { c: '#e2e8f0', t: '' },
  { c: '#fda5a5', t: '⏵⏵ bypass permissions on' }, { c: '#94a3b8', t: '  (shift+tab to cycle)', append: true },
  { c: '#e2e8f0', t: '' },
  { c: '#7dd3fc', t: '> pridej resume button do TerminalScreen' },
  { c: '#e2e8f0', t: '' },
  { c: '#a78bfa', t: '● Read' }, { c: '#94a3b8', t: '(shared/src/commonMain/kotlin/com/clauderemote/ui/TerminalScreen.kt)', append: true },
  { c: '#94a3b8', t: '  ⎿  Read 1842 lines (ctrl+r to expand)' },
  { c: '#e2e8f0', t: '' },
  { c: '#e2e8f0', t: '● Vidím existující strukturu. Resume button přidám' },
  { c: '#e2e8f0', t: '  vedle ConnectButton kolem řádky 412. Použiju' },
  { c: '#e2e8f0', t: '  Icons.Default.PlayArrow.' },
  { c: '#e2e8f0', t: '' },
  { c: '#86efac', t: '● Edit' }, { c: '#94a3b8', t: '(shared/src/commonMain/kotlin/com/clauderemote/ui/TerminalScreen.kt)', append: true },
  { c: '#86efac', t: '  ⎿  +6 -0 ' }, { c: '#94a3b8', t: 'lines added', append: true },
  { c: '#e2e8f0', t: '' },
  { c: '#e2e8f0', t: '● Hotovo. Mám rovnou napsat unit test pro resume()?' },
];

function RawTerminal({ scheme = 'default', showCursor = true }) {
  const ref = useRefTerm(null);
  useEffectTerm(() => {
    if (ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, []);
  // Group lines, since some are appended
  const grouped = [];
  let buf = [];
  RAW_TERMINAL_LINES.forEach((seg) => {
    if (seg.append) { buf.push(seg); }
    else { if (buf.length) grouped.push(buf); buf = [seg]; }
  });
  if (buf.length) grouped.push(buf);

  const schemeMap = {
    default:        { bg: '#0a0e1a', fg: '#d4dae7' },
    'solarized-dark': { bg: '#002b36', fg: '#93a1a1' },
    dracula:        { bg: '#282a36', fg: '#f8f8f2' },
    monokai:        { bg: '#272822', fg: '#f8f8f2' },
    linux:          { bg: '#000000', fg: '#aaaaaa' },
  };
  const colors = schemeMap[scheme] || schemeMap.default;

  return (
    <div className="term-screen" ref={ref} style={{ background: colors.bg, color: colors.fg }}>
      {grouped.map((segs, i) => (
        <div key={i} className="term-line">
          {segs.map((s, j) => (
            <span key={j} style={{ color: s.c }}>{s.t}</span>
          ))}
        </div>
      ))}
      {showCursor && (
        <div className="term-line">
          <span style={{ color: '#7dd3fc' }}>{'›'} </span>
          <span className="term-cursor" />
        </div>
      )}
      <div style={{
        background: '#1aff8a', color: '#062014',
        fontWeight: 600, padding: '2px 8px',
        marginTop: 8, marginInline: -12,
        display: 'flex', justifyContent: 'space-between',
        fontSize: 11,
      }}>
        <span>[claude-redesign:claude*]</span>
        <span>"✻ Claude Code" 13:09 03-Apr-26</span>
      </div>
    </div>
  );
}

// ============================================================
// Transcript view — structured "chat" alternative
// ============================================================
function TranscriptView() {
  const ref = useRefTerm(null);
  useEffectTerm(() => {
    if (ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, []);
  return (
    <div className="transcript" ref={ref}>
      {TRANSCRIPT.map((m, i) => {
        if (m.role === 'meta') {
          return (
            <div key={i} style={{
              fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--text-dim)',
              padding: '2px 6px', textAlign: 'center',
            }}>
              {m.text}
            </div>
          );
        }
        if (m.role === 'tool') {
          return (
            <div key={i} className="msg msg-tool">
              {m.text}
            </div>
          );
        }
        const cls = m.role === 'user' ? 'msg-user' : 'msg-claude';
        return (
          <div key={i} className={`msg ${cls}`}>
            <div style={{ whiteSpace: 'pre-wrap' }}>{renderMd(m.text)}</div>
          </div>
        );
      })}
      <div className="msg msg-claude" style={{ opacity: 0.7 }}>
        <span style={{ display: 'inline-flex', gap: 3, alignItems: 'center' }}>
          <span className="loading-dot" />
          <span className="loading-dot" style={{ animationDelay: '0.15s' }} />
          <span className="loading-dot" style={{ animationDelay: '0.3s' }} />
        </span>
      </div>
      <style>{`
        .loading-dot {
          width: 5px; height: 5px; border-radius: 50%;
          background: var(--text-dim); display: inline-block;
          animation: loadDot 1s ease-in-out infinite;
        }
        @keyframes loadDot { 0%,100% { opacity: 0.3; } 50% { opacity: 1; } }
      `}</style>
    </div>
  );
}

function renderMd(text) {
  // very basic — split on triple-backtick code blocks
  const parts = text.split(/```(\w+)?\n?/);
  const out = [];
  for (let i = 0; i < parts.length; i++) {
    if (i % 3 === 0) {
      out.push(<span key={i}>{parts[i]}</span>);
    } else if (i % 3 === 2) {
      out.push(<pre key={i}><code>{parts[i]}</code></pre>);
    }
  }
  return out;
}

// ============================================================
// TerminalScreen — orchestrates tabs, view, control bar, input
// ============================================================
function TerminalScreen({
  sessionId, tweaks, onBack, onOpenPalette, onOpenInput,
}) {
  const initialSession = SESSIONS.find((s) => s.id === sessionId) || SESSIONS[0];
  const [activeTab, setActiveTab] = useStateTerm(initialSession.id);
  const [view, setView] = useStateTerm(tweaks.terminalView); // 'raw' | 'transcript'
  const [draft, setDraft] = useStateTerm('');
  const [showModePop, setShowModePop] = useStateTerm(false);
  const [showModelPop, setShowModelPop] = useStateTerm(false);
  const [drawer, setDrawer] = useStateTerm(false);
  const [drawerQ, setDrawerQ] = useStateTerm('');

  useEffectTerm(() => { setView(tweaks.terminalView); }, [tweaks.terminalView]);

  const session = SESSIONS.find((s) => s.id === activeTab) || initialSession;
  const srv = SERVERS.find((s) => s.id === session.serverId);
  const tabs = SESSIONS;
  const idx = tabs.findIndex((t) => t.id === activeTab);

  return (
    <>
      <Topbar
        left={
          <button className="icon-btn" onClick={() => setDrawer(true)} title="Sessions">
            <Icon name="menu" />
          </button>
        }
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            <StatusDot activity={session.activity} />
            <span style={{ fontSize: 13, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {session.alias || `${srv.name}:${session.folder.split('/').pop()}`}
            </span>
          </div>
        }
        right={
          <>
            <button className="icon-btn" onClick={() => setView(view === 'raw' ? 'transcript' : 'raw')} title="Toggle view">
              <Icon name={view === 'raw' ? 'book' : 'terminal'} />
            </button>
            <button className="icon-btn" onClick={onOpenPalette} title="Command palette">
              <Icon name="dots" />
            </button>
          </>
        }
      />

      {/* Compact crumb bar — shows current session + prev/next + counter */}
      <div className="crumb-bar">
        <button className="crumb-toggle" onClick={() => setDrawer(true)}>
          <Icon name="menu" size={12} />
          Sessions
        </button>
        <div className="crumb-here">
          <span style={{ color: 'var(--text-dim)' }}>{srv.name}</span>
          <span style={{ opacity: 0.4 }}>:</span>
          <strong>{session.folder.split('/').pop()}</strong>
          {session.alias && <span style={{ color: 'var(--accent)' }}>·{session.alias}</span>}
        </div>
        <div className="crumb-nav">
          <button onClick={() => setActiveTab(tabs[Math.max(0, idx - 1)].id)} disabled={idx === 0} title="Prev session">
            <Icon name="chevleft" size={14} />
          </button>
          <span className="crumb-pos">{idx + 1}/{tabs.length}</span>
          <button onClick={() => setActiveTab(tabs[Math.min(tabs.length - 1, idx + 1)].id)} disabled={idx === tabs.length - 1} title="Next session">
            <Icon name="chevright" size={14} />
          </button>
        </div>
      </div>

      {/* Body */}
      <div className="term-host">
        {view === 'raw' ? (
          <RawTerminal scheme={tweaks.colorScheme} />
        ) : (
          <TranscriptView />
        )}
      </div>

      {/* Control bar */}
      <div className="control-bar" style={{ position: 'relative' }}>
        {/* Mode/model pop */}
        {(showModePop || showModelPop) && (
          <div style={{
            position: 'absolute', bottom: '100%', left: 12, right: 12,
            background: 'var(--surface)', border: '1px solid var(--border)',
            borderRadius: 12, padding: 8, marginBottom: 6,
            boxShadow: '0 12px 30px rgba(0,0,0,0.5)', zIndex: 10,
          }}>
            <div style={{ fontSize: 10, color: 'var(--text-dim)', textTransform: 'uppercase', letterSpacing: 0.5, padding: '4px 8px' }}>
              {showModePop ? 'Mode' : 'Model'}
            </div>
            {(showModePop ? MODES : MODELS).map((o) => (
              <div key={o.k} onClick={() => { setShowModePop(false); setShowModelPop(false); }}
                style={{
                  padding: '8px 10px', borderRadius: 8, cursor: 'pointer',
                  display: 'flex', alignItems: 'center', gap: 8,
                  fontSize: 12,
                  background: (showModePop ? session.mode : session.model) === o.k ? 'var(--tint-accent)' : 'transparent',
                  color: (showModePop ? session.mode : session.model) === o.k ? 'var(--accent)' : 'var(--text)',
                }}>
                {showModePop && <span style={{ fontFamily: 'var(--font-mono)', width: 14 }}>{o.glyph}</span>}
                <span style={{ flex: 1 }}>{o.label}</span>
                {showModePop && <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>{o.desc}</span>}
              </div>
            ))}
          </div>
        )}

        {/* Status row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 2px', fontSize: 11 }}>
          <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 6,
            color: activityMeta[session.activity].color, fontWeight: 600,
            textTransform: 'uppercase', letterSpacing: 0.6, fontSize: 10,
          }}>
            <StatusDot activity={session.activity} />
            {activityMeta[session.activity].label}
          </span>
          <span style={{ flex: 1, color: 'var(--text-dim)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontFamily: 'var(--font-mono)' }}>
            {session.lastLine}
          </span>
          <span style={{ color: 'var(--text-dim)', fontVariantNumeric: 'tabular-nums' }}>
            {fmtCost(session.cost)}
          </span>
        </div>

        {/* Mode + model chips */}
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'nowrap', overflowX: 'auto' }}>
          <button className="btn btn-sm btn-outline" style={{ padding: '4px 10px', fontSize: 11 }} onClick={() => setShowModePop((v) => { setShowModelPop(false); return !v; })}>
            <span style={{ color: 'var(--text-dim)', fontSize: 10 }}>mode</span>
            <strong style={{ marginLeft: 4 }}>{MODES.find((m) => m.k === session.mode).label}</strong>
          </button>
          <button className="btn btn-sm btn-outline" style={{ padding: '4px 10px', fontSize: 11 }} onClick={() => setShowModelPop((v) => { setShowModePop(false); return !v; })}>
            <span style={{ color: 'var(--text-dim)', fontSize: 10 }}>model</span>
            <strong style={{ marginLeft: 4 }}>{MODELS.find((m) => m.k === session.model).label}</strong>
          </button>
          <div style={{ flex: 1 }} />
          <button className="btn btn-sm btn-ghost" style={{ padding: '4px 8px', fontSize: 11, border: '1px solid var(--border)' }} title="Show slash commands" onClick={onOpenPalette}>/cmd</button>
        </div>

        {/* Special keys row — these are unreachable from Gboard, so we surface them */}
        <div className="keys-row">
          <button className="kkey" title="Escape (interrupt)">Esc</button>
          <button className="kkey" title="Tab">Tab</button>
          <button className="kkey kkey-arrow" title="Up arrow">↑</button>
          <button className="kkey kkey-arrow" title="Down arrow">↓</button>
          <button className="kkey kkey-arrow" title="Left arrow">←</button>
          <button className="kkey kkey-arrow" title="Right arrow">→</button>
          <button className="kkey" title="Insert /">/</button>
          <button className="kkey" title="Ctrl-C (SIGINT)">⌃C</button>
          <button className="kkey" title="Ctrl-D (EOF)">⌃D</button>
          <button className="kkey kkey-more" title="More keys" onClick={onOpenPalette}>···</button>
        </div>

        {/* Input row */}
        <div className="input-row">
          <input
            className="input"
            placeholder="Message or /command…"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onFocus={onOpenInput}
            style={{ fontFamily: draft.startsWith('/') ? 'var(--font-mono)' : 'inherit' }}
          />
          <button className="btn send-btn" onClick={() => setDraft('')} disabled={!draft.trim()} style={{ opacity: draft.trim() ? 1 : 0.4 }}>
            <Icon name="send" size={16} />
          </button>
        </div>
      </div>

      {/* Vertical session drawer */}
      <SessionDrawer
        open={drawer}
        onClose={() => setDrawer(false)}
        activeId={activeTab}
        query={drawerQ}
        onQuery={setDrawerQ}
        onPick={(id) => { setActiveTab(id); setDrawer(false); }}
        onNew={() => { setDrawer(false); onBack(); }}
      />
    </>
  );
}

// ============================================================
// SESSION DRAWER — vertical list, groups by server
// ============================================================
function SessionDrawer({ open, onClose, activeId, query, onQuery, onPick, onNew }) {
  // Filter
  const matches = SESSIONS.filter((s) => {
    if (!query.trim()) return true;
    const q = query.toLowerCase();
    const srv = SERVERS.find((x) => x.id === s.serverId);
    return (
      s.folder.toLowerCase().includes(q) ||
      (s.alias || '').toLowerCase().includes(q) ||
      srv.name.toLowerCase().includes(q) ||
      s.mode.toLowerCase().includes(q)
    );
  });
  // Group by server
  const groups = {};
  matches.forEach((s) => {
    (groups[s.serverId] = groups[s.serverId] || []).push(s);
  });

  return (
    <div className={`drawer-host ${open ? 'open' : ''}`}>
      <div className="drawer-backdrop" onClick={onClose} />
      <div className="drawer">
        <div className="drawer-header">
          <Icon name="terminal" size={16} />
          <h2>Sessions</h2>
          <span className="count">{SESSIONS.length}</span>
          <button className="icon-btn" onClick={onClose} style={{ marginLeft: 4 }}>
            <Icon name="x" size={16} />
          </button>
        </div>
        <div className="drawer-search">
          <input
            placeholder="Filter…"
            value={query}
            onChange={(e) => onQuery(e.target.value)}
            autoFocus={open}
          />
        </div>
        <div className="drawer-list">
          {Object.entries(groups).map(([sid, list]) => {
            const srv = SERVERS.find((x) => x.id === sid);
            return (
              <div key={sid}>
                <div className="drawer-group-label">
                  <ServerGlyph name={srv.name} size={14} />
                  {srv.name}
                  <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 10, marginLeft: 'auto', textTransform: 'none', letterSpacing: 0 }}>
                    {list.length}
                  </span>
                </div>
                {list.map((s) => {
                  const modeShort = s.mode === 'YOLO' ? 'YOLO'
                    : s.mode === 'PLAN' ? 'PLAN'
                    : s.mode === 'AUTO_ACCEPT' ? 'AUTO' : 'NORM';
                  const modeCls = s.mode === 'YOLO' ? 'yolo' : s.mode === 'PLAN' ? 'plan' : s.mode === 'AUTO_ACCEPT' ? 'auto' : '';
                  return (
                    <div key={s.id}
                      className={`drawer-item ${s.id === activeId ? 'active' : ''}`}
                      onClick={() => onPick(s.id)}>
                      <div className="di-icon">
                        <StatusDot activity={s.activity} />
                      </div>
                      <div className="di-main">
                        <div className="di-name">
                          {s.alias || s.folder.split('/').pop()}
                        </div>
                        <div className="di-sub">{s.folder}</div>
                      </div>
                      <div className="di-meta">
                        <span className={`di-mode ${modeCls}`}>{modeShort}</span>
                        <span className="di-time">{s.duration}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            );
          })}
          {matches.length === 0 && (
            <div style={{ padding: 20, textAlign: 'center', color: 'var(--text-dim)', fontSize: 12 }}>
              No sessions match "{query}"
            </div>
          )}
        </div>
        <div className="drawer-footer">
          <button className="btn btn-outline btn-sm" style={{ flex: 1 }} onClick={onNew}>
            <Icon name="plus" size={12} /> New session
          </button>
          <button className="btn btn-ghost btn-sm" style={{ border: '1px solid var(--border)' }} title="Reattach all">
            <Icon name="refresh" size={12} />
          </button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { TerminalScreen, RawTerminal, TranscriptView, SessionDrawer });
