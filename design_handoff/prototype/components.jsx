// components.jsx — shared UI primitives used across screens

const { useState: useStateC, useEffect: useEffectC, useRef: useRefC, useMemo: useMemoC, useCallback: useCallbackC } = React;

// ── Status dot ──────────────────────────────────────────────
function StatusDot({ activity }) {
  return <span className={`dot ${activityMeta[activity].dot}`} />;
}

function StatusBar({ activity }) {
  return <span className={`status-bar status-bar-${activity}`} />;
}

function StatusPill({ activity }) {
  const m = activityMeta[activity];
  const cls = activity === 'working' ? 'pill-yellow'
    : activity === 'ready' ? 'pill-green'
    : activity === 'approval' ? 'pill-orange'
    : activity === 'disconnected' ? 'pill-red' : 'pill';
  return (
    <span className={`pill ${cls} status-pill`}>
      <StatusDot activity={activity} />
      {m.label}
    </span>
  );
}

// Heatmap — array of activity strings rendered as colored cells
function ActivityHeatmap({ history, max = 24 }) {
  const cells = history.slice(-max);
  const cls = (a) => ({
    working: 'hw', approval: 'ha',
    ready: 'h3', idle: '', disconnected: 'ha',
  })[a] || '';
  return (
    <div className="heatmap">
      {cells.map((c, i) => (
        <span key={i} className={`heatmap-cell ${cls(c)}`} />
      ))}
    </div>
  );
}

// ── Sparkline (for usage charts) ────────────────────────────
function Sparkline({ values, color = '#7dd3fc', fill = true, height = 36, smooth = true }) {
  const ref = useRefC(null);
  const [w, setW] = useStateC(220);
  useEffectC(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver(() => setW(ref.current.clientWidth));
    ro.observe(ref.current); setW(ref.current.clientWidth);
    return () => ro.disconnect();
  }, []);
  const max = Math.max(...values, 0.001);
  const n = values.length;
  const points = values.map((v, i) => [(i / (n - 1)) * w, height - (v / max) * (height - 6) - 2]);
  const path = smooth
    ? points.reduce((acc, [x, y], i) => {
        if (i === 0) return `M ${x} ${y}`;
        const [px, py] = points[i - 1];
        const cx = (px + x) / 2;
        return `${acc} C ${cx} ${py}, ${cx} ${y}, ${x} ${y}`;
      }, '')
    : 'M ' + points.map((p) => p.join(' ')).join(' L ');
  const areaPath = path + ` L ${w} ${height} L 0 ${height} Z`;
  return (
    <svg ref={ref} className="sparkline" viewBox={`0 0 ${w} ${height}`} preserveAspectRatio="none">
      {fill && <path d={areaPath} fill={color} opacity="0.18" />}
      <path d={path} stroke={color} strokeWidth="1.5" fill="none" strokeLinecap="round" />
    </svg>
  );
}

// ── Lucide-style minimal SVG icons (mono stroke) ───────────
function Icon({ name, size = 18, className }) {
  const s = size;
  const stroke = 'currentColor';
  const props = { width: s, height: s, viewBox: '0 0 24 24', fill: 'none', stroke, strokeWidth: 1.75, strokeLinecap: 'round', strokeLinejoin: 'round', className };
  const i = {
    menu:     <><line x1="3" y1="6"  x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></>,
    settings: <><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></>,
    plus:     <><line x1="12" y1="5"  x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></>,
    play:     <polygon points="6 4 20 12 6 20 6 4"/>,
    star:     <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>,
    trash:    <><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></>,
    pencil:   <><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></>,
    chevright:<polyline points="9 18 15 12 9 6"/>,
    chevleft: <polyline points="15 18 9 12 15 6"/>,
    chevdown: <polyline points="6 9 12 15 18 9"/>,
    server:   <><rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></>,
    folder:   <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>,
    terminal: <><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></>,
    sliders:  <><line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/></>,
    send:     <><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></>,
    search:   <><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></>,
    x:        <><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></>,
    back:     <><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></>,
    arrowup:  <><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></>,
    cpu:      <><rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><line x1="9" y1="1" x2="9" y2="4"/><line x1="15" y1="1" x2="15" y2="4"/><line x1="9" y1="20" x2="9" y2="23"/><line x1="15" y1="20" x2="15" y2="23"/><line x1="20" y1="9" x2="23" y2="9"/><line x1="20" y1="14" x2="23" y2="14"/><line x1="1" y1="9" x2="4" y2="9"/><line x1="1" y1="14" x2="4" y2="14"/></>,
    bolt:     <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>,
    history:  <><path d="M3 3v5h5"/><path d="M3.05 13A9 9 0 1 0 6 5.3L3 8"/><polyline points="12 7 12 12 16 14"/></>,
    expand:   <><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></>,
    close:    <><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/></>,
    dots:     <><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5"  cy="12" r="1"/></>,
    key:      <><circle cx="7" cy="14" r="4"/><line x1="9.5" y1="11.5" x2="20" y2="1"/><line x1="14" y1="7" x2="17" y2="10"/></>,
    lock:     <><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></>,
    refresh:  <><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></>,
    file:     <><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></>,
    book:     <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V2H6.5A2.5 2.5 0 0 0 4 4.5v15zM4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5H6.5A2.5 2.5 0 0 0 4 19.5z"/>,
    eye:      <><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></>,
  }[name] || null;
  return <svg {...props}>{i}</svg>;
}

// ── Topbar (in-app) ─────────────────────────────────────────
function Topbar({ left, right, title }) {
  return (
    <div className="topbar">
      {left}
      {title && <div className="brand">{title}</div>}
      <div className="spacer" />
      {right}
    </div>
  );
}

// ── Server logo: small colorful tile w/ initials ────────────
function ServerGlyph({ name, size = 32 }) {
  const initials = name.split('-').slice(0, 2).map(s => s[0]).join('').toUpperCase();
  const h = (name.charCodeAt(0) * 7 + name.charCodeAt(name.length-1)) % 360;
  const bg = `linear-gradient(135deg, oklch(70% 0.18 ${h}), oklch(55% 0.20 ${(h+40)%360}))`;
  return (
    <div style={{
      width: size, height: size, borderRadius: size * 0.32,
      background: bg, display: 'grid', placeItems: 'center',
      fontFamily: 'var(--font-mono)', fontSize: size * 0.36,
      fontWeight: 700, color: 'rgba(0,0,0,0.7)',
      letterSpacing: '-0.5px', flexShrink: 0,
      boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.4)',
    }}>
      {initials}
    </div>
  );
}

// ── Generic Card (variant-aware via parent) ─────────────────
function Card({ children, onClick, style, className = '' }) {
  return (
    <div className={`card ${className}`} onClick={onClick} style={style}>
      {children}
    </div>
  );
}

// ── Section header ──────────────────────────────────────────
function Section({ title, children, right, pad = true }) {
  return (
    <div className="section" style={pad ? {} : { padding: 0 }}>
      {(title || right) && (
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 10 }}>
          {title && <h2 className="section-title">{title}</h2>}
          {right}
        </div>
      )}
      {children}
    </div>
  );
}

// ── Pill ────────────────────────────────────────────────────
function Pill({ children, kind = '', glyph }) {
  const cls = kind ? `pill pill-${kind}` : 'pill';
  return <span className={cls}>{glyph && <span>{glyph}</span>}{children}</span>;
}

// ── Segmented ───────────────────────────────────────────────
function Segmented({ value, options, onChange, name = 'seg' }) {
  return (
    <div className="seg">
      {options.map((o) => {
        const v = typeof o === 'string' ? o : o.k;
        const l = typeof o === 'string' ? o : o.label;
        const checked = v === value;
        return (
          <label key={v} className={checked ? 'checked' : ''} onClick={() => onChange(v)}>
            {l}
          </label>
        );
      })}
    </div>
  );
}

Object.assign(window, {
  StatusDot, StatusBar, StatusPill, ActivityHeatmap,
  Sparkline, Icon, Topbar, ServerGlyph, Card, Section, Pill, Segmented,
});
