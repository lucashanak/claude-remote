// screens-main.jsx — Launcher, Connect, Server-edit screens

const { useState: useStateMain } = React;

// ============================================================
// LAUNCHER — Active sessions hero (per user 'decide for me')
// ============================================================
function LauncherScreen({ statusViz, density, onNav, onOpenSession, onOpenServer, onOpenSettings, onOpenUsage, onOpenPalette }) {
  const activeSessions = SESSIONS.filter((s) => s.activity !== 'disconnected');
  const dense = density === 'dense';

  return (
    <>
      <Topbar
        left={
          <button className="icon-btn" onClick={onOpenPalette} aria-label="Command palette">
            <Icon name="menu" />
          </button>
        }
        title={<span className="brand">Claude Remote</span>}
        right={
          <>
            <button className="icon-btn" onClick={onOpenUsage} aria-label="Usage">
              <Icon name="cpu" />
            </button>
            <button className="icon-btn" onClick={onOpenSettings} aria-label="Settings">
              <Icon name="settings" />
            </button>
          </>
        }
      />
      <div className="app-scroll">
        <Section
          title={`Active sessions · ${activeSessions.length}`}
          right={<a style={{ fontSize: 12, color: 'var(--text-dim)', cursor: 'pointer' }} onClick={onOpenUsage}>Usage ›</a>}
        >
          <div className="card-stack">
            {activeSessions.map((s) => {
              const srv = SERVERS.find((x) => x.id === s.serverId);
              if (dense) {
                return (
                  <Card key={s.id} onClick={() => onOpenSession(s.id)} className="launcher-sess-row" style={{ cursor: 'pointer', display: 'grid', gridTemplateColumns: 'auto 1fr auto auto', gap: 8, alignItems: 'center' }}>
                    <StatusDot activity={s.activity} />
                    <div style={{ minWidth: 0, display: 'flex', alignItems: 'baseline', gap: 6 }}>
                      <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {s.alias || s.folder.split('/').pop()}
                      </span>
                      <span style={{ fontSize: 10.5, color: 'var(--text-dim)', fontFamily: 'var(--font-mono)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                        {srv.name}
                      </span>
                    </div>
                    <span style={{
                      fontSize: 9, fontWeight: 700, letterSpacing: 0.04, textTransform: 'uppercase',
                      padding: '1px 5px', borderRadius: 4,
                      background: s.mode === 'YOLO' ? 'rgba(248,113,113,0.18)' : s.mode === 'PLAN' ? 'rgba(167,139,250,0.18)' : s.mode === 'AUTO_ACCEPT' ? 'rgba(74,222,128,0.16)' : 'rgba(255,255,255,0.06)',
                      color: s.mode === 'YOLO' ? 'var(--red)' : s.mode === 'PLAN' ? 'var(--purple)' : s.mode === 'AUTO_ACCEPT' ? 'var(--green)' : 'var(--text-dim)',
                    }}>
                      {s.mode === 'AUTO_ACCEPT' ? 'AUTO' : s.mode === 'NORMAL' ? 'NORM' : s.mode}
                    </span>
                    <span style={{ fontSize: 10.5, color: 'var(--text-dim)', fontVariantNumeric: 'tabular-nums', minWidth: 36, textAlign: 'right' }}>
                      {s.duration}
                    </span>
                  </Card>
                );
              }
              return (
                <Card key={s.id} onClick={() => onOpenSession(s.id)} className="launcher-sess-row" style={{ cursor: 'pointer' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <ServerGlyph name={srv.name} size={36} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 14, fontWeight: 600 }}>
                        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {s.alias || s.folder.split('/').pop()}
                        </span>
                        <Pill kind={s.mode === 'YOLO' ? 'red' : s.mode === 'PLAN' ? 'purple' : s.mode === 'AUTO_ACCEPT' ? 'green' : ''}>
                          {MODES.find((m) => m.k === s.mode).label}
                        </Pill>
                      </div>
                      <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 2, fontFamily: 'var(--font-mono)' }}>
                        {srv.name}:{s.folder}
                      </div>
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 4 }}>
                      {statusViz === 'pill' && <StatusPill activity={s.activity} />}
                      {statusViz === 'dot' && <StatusDot activity={s.activity} />}
                      {statusViz === 'bar' && <StatusBar activity={s.activity} />}
                      <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>{s.duration}</span>
                    </div>
                  </div>
                  <div className="preview-line">› {s.lastLine}</div>
                  <div className="heatmap-row" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 8 }}>
                    <ActivityHeatmap history={s.history} max={16} />
                    <span style={{ fontSize: 11, color: 'var(--text-dim)', fontVariantNumeric: 'tabular-nums' }}>
                      {fmtCost(s.cost)} · {fmtTokens(s.tokens)}
                    </span>
                  </div>
                </Card>
              );
            })}
          </div>
        </Section>

        <Section title={`Servers · ${SERVERS.length}`}>
          <div className="card-stack">
            {SERVERS.map((srv) => {
              if (dense) {
                return (
                  <Card key={srv.id} onClick={() => onOpenServer(srv.id)} style={{ cursor: 'pointer', display: 'grid', gridTemplateColumns: 'auto 1fr auto auto', gap: 8, alignItems: 'center' }}>
                    <ServerGlyph name={srv.name} size={18} />
                    <div style={{ minWidth: 0 }}>
                      <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text)' }}>{srv.name}</span>
                      {srv.favorite && <span style={{ color: 'var(--yellow)', fontSize: 11, marginLeft: 4 }}>★</span>}
                      <span style={{ fontSize: 10.5, color: 'var(--text-dim)', fontFamily: 'var(--font-mono)', marginLeft: 8 }}>
                        {srv.user}@{srv.host}
                      </span>
                    </div>
                    {srv.activeSessions > 0 && <Pill kind="green">{srv.activeSessions}</Pill>}
                    <button className="btn btn-sm btn-outline" style={{ padding: '2px 8px', fontSize: 10 }} onClick={(e) => { e.stopPropagation(); onNav('connect', srv.id); }}>
                      ▶
                    </button>
                  </Card>
                );
              }
              return (
                <Card key={srv.id} onClick={() => onOpenServer(srv.id)} style={{ cursor: 'pointer' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <ServerGlyph name={srv.name} size={36} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 14, fontWeight: 600 }}>
                        {srv.name}
                        {srv.favorite && <span style={{ color: 'var(--yellow)', fontSize: 12 }}>★</span>}
                        {srv.activeSessions > 0 && (
                          <Pill kind="green">{srv.activeSessions} live</Pill>
                        )}
                      </div>
                      <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 2, fontFamily: 'var(--font-mono)' }}>
                        {srv.user}@{srv.host}{srv.port !== 22 && ':'+srv.port} · {srv.auth === 'KEY' ? '🔑 key' : '○ pw'}
                      </div>
                    </div>
                    <button className="btn btn-sm btn-outline" onClick={(e) => { e.stopPropagation(); onNav('connect', srv.id); }}>
                      Connect
                    </button>
                  </div>
                  {srv.recent.length > 0 && (
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10 }}>
                      {srv.recent.slice(0, 3).map((f) => (
                        <span key={f} style={{
                          fontFamily: 'var(--font-mono)', fontSize: 10.5,
                          padding: '3px 8px', borderRadius: 6,
                          background: 'rgba(255,255,255,0.04)',
                          color: 'var(--text-dim)',
                          border: '1px solid rgba(255,255,255,0.06)',
                        }}>
                          {f.length > 22 ? '…'+f.slice(-21) : f}
                        </span>
                      ))}
                    </div>
                  )}
                </Card>
              );
            })}
          </div>
        </Section>

        <div style={{ height: 80 }} />
      </div>

      <div className="fab" onClick={() => onNav('server-edit')} role="button" aria-label="Add server">
        <Icon name="plus" size={28} />
      </div>
    </>
  );
}

// ============================================================
// CONNECT — folder, mode, model, tmux
// ============================================================
function ConnectScreen({ serverId, onBack, onLaunch }) {
  const srv = SERVERS.find((s) => s.id === serverId) || SERVERS[0];
  const [folder, setFolder] = useStateMain(srv.recent[0] || '~');
  const [mode, setMode] = useStateMain('AUTO_ACCEPT');
  const [model, setModel] = useStateMain('OPUS');
  const [conn, setConn] = useStateMain('MOSH');
  const [tmuxChoice, setTmuxChoice] = useStateMain('new');
  const [alias, setAlias] = useStateMain('');

  const tmuxForServer = TMUX_SESSIONS.slice(0, 3);

  return (
    <>
      <Topbar
        left={<button className="icon-btn" onClick={onBack}><Icon name="back" /></button>}
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <ServerGlyph name={srv.name} size={26} />
            <span style={{ fontSize: 15, fontWeight: 700 }}>{srv.name}</span>
          </div>
        }
        right={<button className="icon-btn"><Icon name="pencil" size={16} /></button>}
      />
      <div className="app-scroll">
        <Section title="Folder">
          <div className="field">
            <input className="input" value={folder} onChange={(e) => setFolder(e.target.value)} />
          </div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10 }}>
            {srv.recent.map((f) => (
              <button key={f} className="btn btn-sm btn-ghost"
                style={{ fontFamily: 'var(--font-mono)', fontSize: 11, border: '1px solid var(--border)', padding: '4px 10px' }}
                onClick={() => setFolder(f)}>
                {f}
              </button>
            ))}
            <button className="btn btn-sm btn-ghost" style={{ fontSize: 11, padding: '4px 10px', border: '1px solid var(--border)' }}>
              <Icon name="folder" size={12} /> Browse
            </button>
          </div>
        </Section>

        <Section title="Claude options">
          <div className="card-stack">
            <div className="field">
              <label>Mode</label>
              <Segmented value={mode} options={MODES} onChange={setMode} />
              <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 4, fontFamily: 'var(--font-mono)' }}>
                {MODES.find((m) => m.k === mode).desc}
              </div>
            </div>
            <div className="field">
              <label>Model</label>
              <Segmented value={model} options={MODELS} onChange={setModel} />
            </div>
            <div className="field">
              <label>Connection</label>
              <Segmented value={conn} options={['SSH', 'MOSH']} onChange={setConn} />
            </div>
            <div className="field">
              <label>Alias (optional)</label>
              <input className="input" value={alias} onChange={(e) => setAlias(e.target.value)} placeholder="redesign, scrape-fix, …" />
            </div>
          </div>
        </Section>

        <Section title={`Tmux · ${tmuxForServer.length} on server`}>
          <div className="card-stack">
            <label className="card" style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }}>
              <input type="radio" name="tmux" checked={tmuxChoice === 'new'} onChange={() => setTmuxChoice('new')} style={{ accentColor: 'var(--accent)' }} />
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 600 }}>New session</div>
                <div style={{ fontSize: 11, color: 'var(--text-dim)', fontFamily: 'var(--font-mono)' }}>
                  claude-{srv.name}-{folder.split('/').pop()}{alias && '--'+alias}
                </div>
              </div>
            </label>
            {tmuxForServer.map((t, i) => (
              <label key={t.name} className="card" style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }}>
                <input type="radio" name="tmux" checked={tmuxChoice === t.name} onChange={() => setTmuxChoice(t.name)} style={{ accentColor: 'var(--accent)' }} />
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ fontSize: 12, fontFamily: 'var(--font-mono)', fontWeight: 600 }}>{t.name}</span>
                    {t.attached && <Pill kind="green">attached</Pill>}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 2 }}>
                    {t.windows} window{t.windows>1?'s':''} · {t.age} old
                  </div>
                </div>
              </label>
            ))}
          </div>
        </Section>

        <Section pad>
          <div style={{
            padding: 10, borderRadius: 8,
            background: 'rgba(0,0,0,0.3)', fontFamily: 'var(--font-mono)',
            fontSize: 11, color: 'var(--text-dim)', overflow: 'hidden',
          }}>
            <div style={{ color: 'var(--text-dim)', fontSize: 10, marginBottom: 4, textTransform: 'uppercase', letterSpacing: 0.5 }}>Will run</div>
            <div style={{ color: 'var(--green)' }}>$ tmux new -A -s {`'`}claude-{srv.name}-{folder.split('/').pop()}{alias && '--'+alias}{`'`}</div>
            <div style={{ color: 'var(--accent)' }}>$ cd {folder} && claude{model !== 'DEFAULT' ? ' --model '+model.toLowerCase() : ''}{mode === 'AUTO_ACCEPT' ? ' --auto-accept' : ''}{mode === 'YOLO' ? ' --dangerously-skip-permissions' : ''}</div>
          </div>
        </Section>

        <Section pad>
          <button className="btn btn-full" onClick={() => onLaunch({ srv, folder, mode, model, conn, alias })}>
            <Icon name="play" size={14} /> Launch Claude
          </button>
        </Section>

        <div style={{ height: 40 }} />
      </div>
    </>
  );
}

// ============================================================
// SERVER EDIT
// ============================================================
function ServerEditScreen({ serverId, onBack, onSave }) {
  const isNew = !serverId;
  const initial = isNew
    ? { name: '', host: '', port: 22, user: '', auth: 'KEY', favorite: false, recent: [] }
    : SERVERS.find((s) => s.id === serverId) || SERVERS[0];
  const [name, setName] = useStateMain(initial.name);
  const [host, setHost] = useStateMain(initial.host);
  const [port, setPort] = useStateMain(initial.port);
  const [user, setUser] = useStateMain(initial.user);
  const [auth, setAuth] = useStateMain(initial.auth);
  const [cf, setCf] = useStateMain(false);

  return (
    <>
      <Topbar
        left={<button className="icon-btn" onClick={onBack}><Icon name="back" /></button>}
        title={<span style={{ fontSize: 15, fontWeight: 700 }}>{isNew ? 'New server' : `Edit ${initial.name}`}</span>}
        right={<button className="btn btn-sm" onClick={onSave}>Save</button>}
      />
      <div className="app-scroll">
        <Section title="Identity">
          <div className="card-stack">
            <div className="field">
              <label>Display name</label>
              <input className="input" placeholder="home-nas, hetzner-eu…" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 10 }}>
              <div className="field">
                <label>Host</label>
                <input className="input" placeholder="192.168.1.7 or hetzner.dev" value={host} onChange={(e) => setHost(e.target.value)} />
              </div>
              <div className="field">
                <label>Port</label>
                <input className="input" type="number" value={port} onChange={(e) => setPort(e.target.value)} />
              </div>
            </div>
            <div className="field">
              <label>Username</label>
              <input className="input" placeholder="lukas, root, deploy…" value={user} onChange={(e) => setUser(e.target.value)} />
            </div>
          </div>
        </Section>

        <Section title="Auth">
          <Segmented value={auth} options={[{ k: 'PASSWORD', label: 'Password' }, { k: 'KEY', label: 'SSH key' }]} onChange={setAuth} />
          {auth === 'PASSWORD' ? (
            <div className="field" style={{ marginTop: 10 }}>
              <label>Password</label>
              <input className="input" type="password" placeholder="••••••••" />
            </div>
          ) : (
            <div style={{ marginTop: 10 }}>
              <div style={{
                background: 'rgba(0,0,0,0.3)', borderRadius: 8, padding: 10,
                fontFamily: 'var(--font-mono)', fontSize: 10.5, color: 'var(--text-dim)',
                lineHeight: 1.4, maxHeight: 100, overflow: 'hidden',
              }}>
                -----BEGIN OPENSSH PRIVATE KEY-----<br/>
                b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAA<br/>
                AAtzc2gtZWQyNTUxOQAAACDxn3rrCt9JcfqYjUM3MXp4f23kkV8YwsoLEKjbjP<br/>
                <span style={{ color: 'var(--text-dim)', opacity: 0.5 }}>…</span>
              </div>
              <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                <button className="btn btn-sm btn-outline">Paste key</button>
                <button className="btn btn-sm btn-ghost" style={{ border: '1px solid var(--border)' }}>Generate</button>
              </div>
            </div>
          )}
        </Section>

        <Section title="Advanced">
          <div className="card-stack">
            <label className="card" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <input type="checkbox" style={{ accentColor: 'var(--accent)' }} />
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 600 }}>Prefer Mosh</div>
                <div style={{ fontSize: 11, color: 'var(--text-dim)' }}>Survives roaming + sleep; requires mosh-server</div>
              </div>
            </label>
            <label className="card" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <input type="checkbox" checked={cf} onChange={(e) => setCf(e.target.checked)} style={{ accentColor: 'var(--accent)' }} />
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 600 }}>Cloudflare proxy</div>
                <div style={{ fontSize: 11, color: 'var(--text-dim)' }}>Route via cloudflared tunnel — no public IP needed</div>
              </div>
            </label>
            {cf && (
              <div className="field">
                <label>Cloudflare token</label>
                <input className="input" type="password" placeholder="eyJhIjoiMzh…" />
              </div>
            )}
            <details>
              <summary style={{ fontSize: 12, color: 'var(--text-dim)', cursor: 'pointer', padding: '6px 4px' }}>
                Port forwarding · 0
              </summary>
              <div style={{ paddingTop: 8, fontSize: 11, color: 'var(--text-dim)' }}>
                None configured. <a style={{ color: 'var(--accent)' }}>+ Add</a>
              </div>
            </details>
          </div>
        </Section>

        <Section title="Claude defaults for this server">
          <div className="card-stack">
            <div className="field">
              <label>Default mode</label>
              <Segmented value="AUTO_ACCEPT" options={MODES} onChange={() => {}} />
            </div>
            <div className="field">
              <label>Default model</label>
              <Segmented value="OPUS" options={MODELS} onChange={() => {}} />
            </div>
            <div className="field">
              <label>Default folder</label>
              <input className="input" defaultValue="~" />
            </div>
            <div className="field">
              <label>Startup command (optional)</label>
              <input className="input" placeholder="source ~/.zshrc &amp;&amp; nvm use 20" />
            </div>
          </div>
        </Section>

        {!isNew && (
          <Section pad>
            <button className="btn btn-danger btn-full">Delete server</button>
          </Section>
        )}

        <div style={{ height: 40 }} />
      </div>
    </>
  );
}

Object.assign(window, { LauncherScreen, ConnectScreen, ServerEditScreen });
