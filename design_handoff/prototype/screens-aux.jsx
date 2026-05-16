// screens-aux.jsx — Settings, Usage dashboard, CommandPalette, ExpandedInput

const { useState: useStateAux } = React;

// ============================================================
// SETTINGS
// ============================================================
function SettingsScreen({ onBack, tweaks }) {
  const [fontSize, setFontSize] = useStateAux(13);
  const [scrollback, setScrollback] = useStateAux(10000);
  const [scheme, setScheme] = useStateAux(tweaks.colorScheme || 'default');
  const [defaultMode, setDefaultMode] = useStateAux('AUTO_ACCEPT');
  const [defaultModel, setDefaultModel] = useStateAux('OPUS');
  const [defaultConn, setDefaultConn] = useStateAux('MOSH');
  const [autoReconn, setAutoReconn] = useStateAux(true);
  const [keepAlive, setKeepAlive] = useStateAux(true);
  const [timeoutS, setTimeoutS] = useStateAux(15);
  const [biometric, setBiometric] = useStateAux(false);

  return (
    <>
      <Topbar
        left={<button className="icon-btn" onClick={onBack}><Icon name="back" /></button>}
        title={<span style={{ fontSize: 15, fontWeight: 700 }}>Settings</span>}
        right={<button className="icon-btn" title="Logs"><Icon name="file" size={16} /></button>}
      />
      <div className="app-scroll">
        <Section title="Terminal">
          <div className="card-stack">
            <SliderRow label="Font size" value={fontSize} unit="px" min={9} max={24} onChange={setFontSize} />
            <SliderRow label="Scrollback" value={scrollback} unit="lines" min={1000} max={50000} step={1000} onChange={setScrollback} />
            <div className="field">
              <label>Color scheme</label>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {['default', 'solarized-dark', 'dracula', 'monokai', 'linux'].map((s) => (
                  <button key={s} className={`btn btn-sm ${s === scheme ? '' : 'btn-ghost'}`}
                    style={{ border: '1px solid var(--border)', fontSize: 11, padding: '6px 10px', fontFamily: 'var(--font-mono)' }}
                    onClick={() => setScheme(s)}>
                    {s}
                  </button>
                ))}
              </div>
              <div style={{ marginTop: 10 }}>
                <SchemePreview scheme={scheme} />
              </div>
            </div>
          </div>
        </Section>

        <Section title="Claude defaults">
          <div className="card-stack">
            <div className="field">
              <label>Default mode</label>
              <Segmented value={defaultMode} options={MODES} onChange={setDefaultMode} />
            </div>
            <div className="field">
              <label>Default model</label>
              <Segmented value={defaultModel} options={MODELS} onChange={setDefaultModel} />
            </div>
          </div>
        </Section>

        <Section title="Connection">
          <div className="card-stack">
            <div className="field">
              <label>Default connection type</label>
              <Segmented value={defaultConn} options={['SSH', 'MOSH']} onChange={setDefaultConn} />
            </div>
            <ToggleRow label="Auto-reconnect" subtitle="Exponential backoff after disconnect" value={autoReconn} onChange={setAutoReconn} />
            <ToggleRow label="Keep alive (background)" subtitle="Wake lock + foreground service" value={keepAlive} onChange={setKeepAlive} />
            <SliderRow label="Connect timeout" value={timeoutS} unit="s" min={5} max={60} onChange={setTimeoutS} />
          </div>
        </Section>

        <Section title="Security">
          <ToggleRow label="Biometric lock" subtitle="Fingerprint / Face ID on launch" value={biometric} onChange={setBiometric} />
        </Section>

        <Section title="Updates">
          <div className="card">
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div>
                <div style={{ fontSize: 13, fontWeight: 600 }}>Claude Remote v0.8.3</div>
                <div style={{ fontSize: 11, color: 'var(--text-dim)' }}>Last checked 2h ago · up to date</div>
              </div>
              <button className="btn btn-sm btn-outline"><Icon name="refresh" size={12} /> Check</button>
            </div>
            <div style={{ marginTop: 10, padding: 8, borderRadius: 6, background: 'rgba(0,0,0,0.25)', fontSize: 11, color: 'var(--text-dim)' }}>
              <span style={{ color: 'var(--green)' }}>✓</span> Delta updates enabled · ~2.4 MB / update
            </div>
          </div>
        </Section>

        <Section title="About">
          <div className="card" style={{ fontSize: 12, color: 'var(--text-dim)' }}>
            <div><strong style={{ color: 'var(--text)' }}>Claude Remote</strong> — Compose Multiplatform · Android + Desktop</div>
            <div style={{ marginTop: 6 }}>SSH/Mosh + tmux + Claude Code CLI. No proprietary backend.</div>
            <div style={{ marginTop: 10, display: 'flex', gap: 12, fontSize: 11 }}>
              <a style={{ color: 'var(--accent)', cursor: 'pointer' }}>github.com/lucashanak/claude-remote</a>
              <a style={{ color: 'var(--accent)', cursor: 'pointer' }}>Docs</a>
              <a style={{ color: 'var(--accent)', cursor: 'pointer' }}>Logs</a>
            </div>
          </div>
        </Section>

        <div style={{ height: 40 }} />
      </div>
    </>
  );
}

function SliderRow({ label, value, unit = '', min, max, step = 1, onChange }) {
  return (
    <div className="field">
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <label>{label}</label>
        <span style={{ fontSize: 12, color: 'var(--text)', fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>
          {value}{unit && ' '+unit}
        </span>
      </div>
      <input type="range" min={min} max={max} step={step} value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        style={{ width: '100%', accentColor: 'var(--accent)' }}/>
    </div>
  );
}

function ToggleRow({ label, subtitle, value, onChange }) {
  return (
    <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 12, cursor: 'pointer' }} onClick={() => onChange(!value)}>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 13, fontWeight: 600 }}>{label}</div>
        {subtitle && <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 2 }}>{subtitle}</div>}
      </div>
      <div style={{
        width: 40, height: 22, borderRadius: 999,
        background: value ? 'var(--accent)' : 'rgba(255,255,255,0.1)',
        position: 'relative', transition: 'background 0.2s', flexShrink: 0,
      }}>
        <div style={{
          position: 'absolute', top: 2, left: value ? 20 : 2,
          width: 18, height: 18, borderRadius: '50%', background: '#fff',
          transition: 'left 0.2s',
        }} />
      </div>
    </div>
  );
}

function SchemePreview({ scheme }) {
  const schemes = {
    default:          { bg: '#0a0e1a', fg: '#d4dae7', accent: '#7dd3fc', red: '#fda5a5', green: '#86efac', purple: '#c4b5fd' },
    'solarized-dark': { bg: '#002b36', fg: '#93a1a1', accent: '#268bd2', red: '#dc322f', green: '#859900', purple: '#6c71c4' },
    dracula:          { bg: '#282a36', fg: '#f8f8f2', accent: '#8be9fd', red: '#ff5555', green: '#50fa7b', purple: '#bd93f9' },
    monokai:          { bg: '#272822', fg: '#f8f8f2', accent: '#66d9ef', red: '#f92672', green: '#a6e22e', purple: '#ae81ff' },
    linux:            { bg: '#000000', fg: '#aaaaaa', accent: '#5555ff', red: '#aa0000', green: '#00aa00', purple: '#aa00aa' },
  };
  const c = schemes[scheme] || schemes.default;
  return (
    <div style={{
      background: c.bg, color: c.fg, padding: 10,
      borderRadius: 8, fontFamily: 'var(--font-mono)', fontSize: 11,
      border: '1px solid var(--border)',
    }}>
      <div><span style={{ color: c.red }}>$</span> <span>claude</span> <span style={{ color: c.accent }}>--model opus</span></div>
      <div><span style={{ color: c.green }}>●</span> Read<span style={{ color: c.purple }}>(file.kt)</span></div>
      <div><span style={{ color: c.accent }}>›</span> message<span className="term-cursor" style={{ background: c.fg }}/></div>
    </div>
  );
}

// ============================================================
// USAGE DASHBOARD
// ============================================================
function UsageScreen({ onBack }) {
  const u = USAGE;
  const [range, setRange] = useStateAux('7d');
  const maxCost = Math.max(...u.daily, 0.01);
  return (
    <>
      <Topbar
        left={<button className="icon-btn" onClick={onBack}><Icon name="back" /></button>}
        title={<span style={{ fontSize: 15, fontWeight: 700 }}>Usage</span>}
        right={<button className="icon-btn"><Icon name="refresh" size={16} /></button>}
      />
      <div className="app-scroll">
        <Section pad>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <Card>
              <div style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.6, color: 'var(--text-dim)', fontWeight: 600 }}>7-day cost</div>
              <div style={{ fontSize: 26, fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: 'var(--accent)', lineHeight: 1, marginTop: 4 }}>
                {fmtCost(u.totalCost7d)}
              </div>
              <div style={{ fontSize: 11, color: 'var(--green)', marginTop: 4 }}>↑ 23% vs prev week</div>
            </Card>
            <Card>
              <div style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.6, color: 'var(--text-dim)', fontWeight: 600 }}>Tokens</div>
              <div style={{ fontSize: 26, fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: 'var(--text)', lineHeight: 1, marginTop: 4 }}>
                {fmtTokens(u.totalTokens7d)}
              </div>
              <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 4 }}>across {SESSIONS.length} sessions</div>
            </Card>
          </div>
        </Section>

        <Section title="Daily spend" right={<Segmented value={range} options={['7d', '30d']} onChange={setRange} />}>
          <Card>
            <div style={{ height: 100, display: 'flex', alignItems: 'flex-end', gap: 4, padding: '4px 0' }}>
              {u.daily.slice(range === '7d' ? -7 : 0).map((v, i) => (
                <div key={i} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
                  <div style={{
                    width: '100%', height: `${(v / maxCost) * 80}px`,
                    background: v > 0 ? 'linear-gradient(180deg, var(--accent), color-mix(in srgb, var(--accent) 30%, transparent))' : 'rgba(255,255,255,0.04)',
                    borderRadius: 4,
                    minHeight: 4,
                    transition: 'height 0.3s',
                  }} />
                  <span style={{ fontSize: 9, color: 'var(--text-dim)' }}>
                    {(range === '7d' ? ['M','T','W','T','F','S','S'] : Array(14).fill('').map((_,j)=>j%2===0?'·':'·'))[i]}
                  </span>
                </div>
              ))}
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontSize: 11, color: 'var(--text-dim)' }}>
              <span>Peak: {fmtCost(maxCost)} on Tue</span>
              <span>Avg: {fmtCost(u.totalCost7d/7)}/day</span>
            </div>
          </Card>
        </Section>

        <Section title="By model">
          <div className="card-stack">
            {u.byModel.map((m) => {
              const pct = (m.cost / u.totalCost7d) * 100;
              return (
                <Card key={m.name}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <div style={{ width: 8, height: 8, borderRadius: 2, background: m.color, flexShrink: 0 }} />
                    <span style={{ fontSize: 13, fontWeight: 600 }}>{m.name}</span>
                    <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>{fmtTokens(m.tokens)}</span>
                    <span style={{ marginLeft: 'auto', fontSize: 13, fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{fmtCost(m.cost)}</span>
                  </div>
                  <div style={{ height: 4, background: 'rgba(255,255,255,0.06)', borderRadius: 2, marginTop: 8, overflow: 'hidden' }}>
                    <div style={{ width: `${pct}%`, height: '100%', background: m.color, borderRadius: 2 }} />
                  </div>
                </Card>
              );
            })}
          </div>
        </Section>

        <Section title="Top sessions">
          <div className="card-stack">
            {u.topSessions.map((t, i) => (
              <Card key={i}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <span style={{ color: 'var(--text-dim)', fontFamily: 'var(--font-mono)', fontSize: 11, fontWeight: 600, width: 18 }}>
                    {String(i+1).padStart(2,'0')}
                  </span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 13, fontWeight: 600, fontFamily: 'var(--font-mono)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {t.folder}
                    </div>
                    <div style={{ fontSize: 10, color: 'var(--text-dim)', marginTop: 2 }}>{t.host} · {fmtTokens(t.tokens)}</div>
                  </div>
                  <span style={{ fontSize: 13, fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: 'var(--accent)' }}>
                    {fmtCost(t.cost)}
                  </span>
                </div>
              </Card>
            ))}
          </div>
        </Section>

        <Section pad>
          <div className="card" style={{ fontSize: 11, color: 'var(--text-dim)' }}>
            <Icon name="bolt" size={12} />{' '}
            Cost estimates are based on local token counting. Actual Anthropic billing may differ.
          </div>
        </Section>

        <div style={{ height: 40 }} />
      </div>
    </>
  );
}

// ============================================================
// COMMAND PALETTE
// ============================================================
function CommandPalette({ onClose, onPick }) {
  const [q, setQ] = useStateAux('');
  const filtered = COMMANDS.filter((c) =>
    !q || c.cmd.toLowerCase().includes(q.toLowerCase()) || (c.desc || '').toLowerCase().includes(q.toLowerCase())
  );

  // group
  const groups = {};
  filtered.forEach((c) => {
    const k = c.g === '/' ? 'Slash commands'
      : c.g === '⌘' ? 'Shortcuts'
      : c.g === 'tmpl' ? 'Templates' : 'Other';
    (groups[k] = groups[k] || []).push(c);
  });

  return (
    <div className="overlay-backdrop" onClick={onClose}>
      <div className="palette" onClick={(e) => e.stopPropagation()}>
        <div className="search">
          <Icon name="search" size={16} />
          <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Type / for slash commands, ⌘ for shortcuts…" />
          <kbd style={{ background: 'rgba(255,255,255,0.06)', padding: '2px 6px', borderRadius: 4, fontSize: 10, color: 'var(--text-dim)' }}>esc</kbd>
        </div>
        <div className="items">
          {Object.entries(groups).map(([name, items]) => (
            <div key={name}>
              <div style={{ padding: '8px 10px 4px', fontSize: 10, color: 'var(--text-dim)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>
                {name}
              </div>
              {items.map((it, i) => (
                <div key={name+i} className="item" onClick={() => { onPick && onPick(it); onClose(); }}>
                  <span className="glyph">{it.g === '⌘' ? '⌘' : it.g === '/' ? '/' : it.g === 'tmpl' ? '✎' : '·'}</span>
                  <span style={{ fontWeight: 500 }}>{it.cmd}</span>
                  {it.desc && <span style={{ color: 'var(--text-dim)', fontSize: 11, marginLeft: 8 }}>{it.desc}</span>}
                  {it.kbd && <kbd>{it.kbd}</kbd>}
                </div>
              ))}
            </div>
          ))}
          {filtered.length === 0 && (
            <div style={{ padding: 20, textAlign: 'center', color: 'var(--text-dim)', fontSize: 12 }}>
              No matches for "{q}"
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ============================================================
// EXPANDED INPUT — templates + history
// ============================================================
function ExpandedInput({ onClose, onSend }) {
  const [text, setText] = useStateAux('');
  const [tab, setTab] = useStateAux('templates');

  return (
    <div className="overlay-backdrop" style={{ alignItems: 'flex-end', padding: 0 }} onClick={onClose}>
      <div onClick={(e) => e.stopPropagation()} style={{
        width: '100%', maxHeight: '85%',
        background: 'var(--surface)', borderTop: '1px solid var(--border)',
        borderRadius: '16px 16px 0 0', display: 'flex', flexDirection: 'column',
      }}>
        <div style={{ padding: '10px 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid var(--border)' }}>
          <div style={{ width: 36, height: 4, background: 'var(--border)', borderRadius: 2, margin: '0 auto', position: 'absolute', left: '50%', transform: 'translateX(-50%)', top: 6 }} />
          <span style={{ fontSize: 13, fontWeight: 600 }}>Compose</span>
          <button className="icon-btn" onClick={onClose}><Icon name="x" size={16} /></button>
        </div>

        <textarea
          className="input"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Message or paste code, /command, …"
          autoFocus
          style={{ resize: 'none', minHeight: 120, borderRadius: 0, border: 0, padding: 14, fontFamily: text.startsWith('/') ? 'var(--font-mono)' : 'inherit' }}
        />

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 14px', borderTop: '1px solid var(--border)', borderBottom: '1px solid var(--border)' }}>
          <Segmented value={tab} options={[{ k: 'templates', label: 'Templates' }, { k: 'history', label: 'History' }]} onChange={setTab} />
          <span style={{ fontSize: 10, color: 'var(--text-dim)', fontVariantNumeric: 'tabular-nums' }}>{text.length} chars</span>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: 10, minHeight: 140 }}>
          {tab === 'templates' && (
            <div className="card-stack">
              {TEMPLATES.map((t) => (
                <Card key={t.name} onClick={() => setText(t.body)} className="interactive" style={{ cursor: 'pointer' }}>
                  <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 4 }}>{t.name}</div>
                  <div style={{ fontSize: 11, color: 'var(--text-dim)', overflow: 'hidden', textOverflow: 'ellipsis', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
                    {t.body}
                  </div>
                </Card>
              ))}
            </div>
          )}
          {tab === 'history' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {HISTORY.map((h, i) => (
                <div key={i} className="card" style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px' }} onClick={() => setText(h)}>
                  <Icon name="history" size={12} />
                  <span style={{ fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{h}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div style={{ padding: 10, display: 'flex', gap: 8, alignItems: 'center', borderTop: '1px solid var(--border)' }}>
          <button className="btn btn-ghost btn-sm" style={{ border: '1px solid var(--border)' }}>
            <Icon name="file" size={12} /> Attach
          </button>
          <button className="btn btn-ghost btn-sm" style={{ border: '1px solid var(--border)' }}>
            <Icon name="bolt" size={12} /> /command
          </button>
          <div style={{ flex: 1 }} />
          <button className="btn" onClick={() => { onSend && onSend(text); onClose(); }} disabled={!text.trim()} style={{ opacity: text.trim() ? 1 : 0.4 }}>
            <Icon name="send" size={12} /> Send
          </button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { SettingsScreen, UsageScreen, CommandPalette, ExpandedInput });
