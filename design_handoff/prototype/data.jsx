// data.jsx — mock data + helpers, exposed on window

const SERVERS = [
  {
    id: 's1', name: 'home-nas', host: '192.168.1.7', port: 22,
    user: 'lukas', auth: 'KEY', favorite: true,
    recent: ['~/claude-remote', '~/vscode-android', '~/dotfiles'],
    activeSessions: 2,
  },
  {
    id: 's2', name: 'hetzner-eu', host: 'ax42.lucash.dev', port: 22,
    user: 'deploy', auth: 'KEY', favorite: true,
    recent: ['~/actions', '~/music-seeker'],
    activeSessions: 1,
  },
  {
    id: 's3', name: 'macbook-vm', host: '10.0.0.42', port: 2222,
    user: 'lukas', auth: 'PASSWORD', favorite: false,
    recent: ['~/work/raylyst'],
    activeSessions: 0,
  },
  {
    id: 's4', name: 'pi-cluster', host: '192.168.1.50', port: 22,
    user: 'pi', auth: 'KEY', favorite: false,
    recent: ['~/dashboards'],
    activeSessions: 0,
  },
];

const SESSIONS = [
  { id: 'sess-1',  serverId: 's1', folder: '~/claude-remote',  alias: 'redesign',    mode: 'YOLO',        model: 'OPUS',   activity: 'working',  duration: '12m',    cost: 0.42, tokens: 28140,
    lastLine: 'Reading shared/src/commonMain/kotlin/com/clauderemote/ui/App.kt…',
    history: ['idle','idle','working','working','approval','working','working','working','ready','working','working','working'] },
  { id: 'sess-2',  serverId: 's1', folder: '~/vscode-android', alias: '',            mode: 'PLAN',        model: 'SONNET', activity: 'approval', duration: '4m',     cost: 0.08, tokens: 6420,
    lastLine: 'Bash: rm -rf node_modules/  (y/n)',
    history: ['working','working','working','working','approval'] },
  { id: 'sess-3',  serverId: 's2', folder: '~/actions',         alias: 'scrape-fix',  mode: 'AUTO_ACCEPT', model: 'OPUS',   activity: 'ready',    duration: '1h 23m', cost: 2.18, tokens: 184200,
    lastLine: '✓ All 24 tests passed in 8.2s',
    history: ['ready','working','working','approval','working','working','working','approval','working','ready'] },
  { id: 'sess-4',  serverId: 's2', folder: '~/music-seeker',    alias: '',            mode: 'AUTO_ACCEPT', model: 'SONNET', activity: 'working',  duration: '34m',    cost: 0.86, tokens: 72100,
    lastLine: 'Writing src/api/deezer.ts (+128 -42)',
    history: ['working','working','working','working','approval','working','working'] },
  { id: 'sess-5',  serverId: 's1', folder: '~/dotfiles',        alias: 'quick-fix',   mode: 'NORMAL',      model: 'HAIKU',  activity: 'ready',    duration: '2m',     cost: 0.01, tokens: 1840,
    lastLine: 'How can I help?',
    history: ['idle','idle','idle','ready'] },
  { id: 'sess-6',  serverId: 's2', folder: '~/booksearch',      alias: '',            mode: 'YOLO',        model: 'OPUS',   activity: 'approval', duration: '18m',    cost: 0.54, tokens: 41200,
    lastLine: 'Confirm: drop table search_history? (y/n)',
    history: ['working','approval','approval'] },
  { id: 'sess-7',  serverId: 's4', folder: '~/dashboards',      alias: 'grafana',     mode: 'PLAN',        model: 'OPUS',   activity: 'working',  duration: '8m',     cost: 0.21, tokens: 18420,
    lastLine: 'Analyzing prometheus query patterns…',
    history: ['working','working','working','working'] },
  { id: 'sess-8',  serverId: 's2', folder: '~/reverse-proxy',   alias: '',            mode: 'AUTO_ACCEPT', model: 'SONNET', activity: 'idle',     duration: '2h 4m',  cost: 0.94, tokens: 82400,
    lastLine: '',
    history: ['ready','idle','idle','idle','idle'] },
  { id: 'sess-9',  serverId: 's3', folder: '~/work/raylyst',    alias: '',            mode: 'NORMAL',      model: 'OPUS',   activity: 'working',  duration: '47m',    cost: 1.34, tokens: 124800,
    lastLine: '● Edit(Services/InvoiceProcessor.cs) +18 -4',
    history: ['working','working','approval','working','working','working','approval','working'] },
  { id: 'sess-10', serverId: 's1', folder: '~/blog',            alias: 'rewrite',     mode: 'PLAN',        model: 'OPUS',   activity: 'ready',    duration: '3h 12m', cost: 3.42, tokens: 312000,
    lastLine: '✓ Plan saved to PLAN.md (7 steps)',
    history: ['working','working','working','approval','working','ready','ready'] },
  { id: 'sess-11', serverId: 's2', folder: '~/genai-course',    alias: 'capstone',    mode: 'AUTO_ACCEPT', model: 'SONNET', activity: 'working',  duration: '24m',    cost: 0.62, tokens: 54800,
    lastLine: 'Running notebooks/04-evals.ipynb…',
    history: ['working','working','working'] },
  { id: 'sess-12', serverId: 's4', folder: '~/yawsp',           alias: '',            mode: 'NORMAL',      model: 'HAIKU',  activity: 'idle',     duration: '14m',    cost: 0.04, tokens: 4200,
    lastLine: '',
    history: ['ready','ready','idle','idle'] },
  { id: 'sess-13', serverId: 's1', folder: '~/fyzio-trebic',    alias: '',            mode: 'AUTO_ACCEPT', model: 'OPUS',   activity: 'disconnected', duration: '—', cost: 0.18, tokens: 16400,
    lastLine: '⨯ ssh: connection closed by remote host',
    history: ['ready','working','disconnected'] },
];

const TMUX_SESSIONS = [
  { name: 'claude-home-nas-claude-remote', windows: 1, attached: true,  age: '12m' },
  { name: 'claude-home-nas-vscode-android', windows: 1, attached: true, age: '4m' },
  { name: 'claude-home-nas-dotfiles--quick-fix', windows: 2, attached: false, age: '2h' },
  { name: 'bg-builds', windows: 3, attached: false, age: '6h' },
];

const MODES = [
  { k: 'NORMAL', label: 'Normal', glyph: '·', desc: 'Approve each step' },
  { k: 'PLAN', label: 'Plan', glyph: '◇', desc: 'Plan before acting' },
  { k: 'AUTO_ACCEPT', label: 'Auto-accept', glyph: '▸', desc: 'Auto-approve safe ops' },
  { k: 'YOLO', label: 'YOLO', glyph: '⚡', desc: '--dangerously-skip-permissions' },
];

const MODELS = [
  { k: 'DEFAULT', label: 'Default' },
  { k: 'OPUS', label: 'Opus' },
  { k: 'SONNET', label: 'Sonnet' },
  { k: 'HAIKU', label: 'Haiku' },
];

const COMMANDS = [
  { g: '/', cmd: '/init', desc: 'Bootstrap CLAUDE.md', kbd: '' },
  { g: '/', cmd: '/clear', desc: 'Clear context', kbd: '' },
  { g: '/', cmd: '/compact', desc: 'Compact transcript', kbd: '' },
  { g: '/', cmd: '/model', desc: 'Switch model', kbd: '' },
  { g: '/', cmd: '/mode', desc: 'Switch mode', kbd: '' },
  { g: '/', cmd: '/help', desc: 'Show help', kbd: '' },
  { g: '⌘', cmd: 'New tab',         desc: 'Open new session',     kbd: '⌘T' },
  { g: '⌘', cmd: 'Close tab',       desc: '',                     kbd: '⌘W' },
  { g: '⌘', cmd: 'Toggle keyboard', desc: 'Show/hide overlay',    kbd: '⌘K' },
  { g: '⌘', cmd: 'Esc',             desc: 'Interrupt Claude',     kbd: 'Esc' },
  { g: '⌘', cmd: 'Ctrl+C',          desc: 'Send SIGINT',          kbd: '⌃C' },
  { g: 'tmpl','cmd': 'Refactor selection', desc: 'Use template',  kbd: '' },
  { g: 'tmpl','cmd': 'Add tests',          desc: 'Use template',  kbd: '' },
  { g: 'tmpl','cmd': 'Explain this code',  desc: 'Use template',  kbd: '' },
];

const TEMPLATES = [
  { name: 'Refactor', body: 'Refactor this for readability. Keep behavior identical, add JSDoc.' },
  { name: 'Add tests', body: 'Add comprehensive unit tests covering edge cases. Use the existing test framework.' },
  { name: 'Explain', body: 'Explain what this code does, step by step, and identify any bugs or smells.' },
  { name: 'Fix lint',  body: 'Fix all lint and type errors. Don\'t change behavior.' },
  { name: 'Bisect',    body: 'A regression appeared between these two commits. Find the offending change.' },
];

const HISTORY = [
  'kde je definovany SshKeyManager?',
  'pridej resume button do TerminalScreen',
  '/plan',
  'zkontroluj test pro CommandFetcher',
  'opravy v ServerEditDialog',
  'precti README a shrn co dela',
];

// Pseudo-transcript for the structured terminal view
const TRANSCRIPT = [
  { role: 'meta',   text: 'claude --model opus --resume 7f2a' },
  { role: 'claude', text: 'Welcome back, Lukáš.\nOpus 4.6 (1M context) · Claude Max\nProject: ~/claude-remote' },
  { role: 'user',   text: 'pridej resume button do TerminalScreen' },
  { role: 'tool',   text: 'Read(shared/src/commonMain/kotlin/com/clauderemote/ui/TerminalScreen.kt)' },
  { role: 'claude', text: 'Vidím existující strukturu. Resume button se hodí vedle stávajícího ConnectButton kolem řádky 412. Přidám:\n\n```kotlin\nIconButton(onClick = { vm.resume() }) {\n  Icon(Icons.Default.PlayArrow, null)\n}\n```' },
  { role: 'tool',   text: 'Edit(shared/src/commonMain/kotlin/com/clauderemote/ui/TerminalScreen.kt) +6 -0' },
  { role: 'claude', text: 'Hotovo. Resume button volá `vm.resume()` který už existuje v SessionOrchestrator. Mám rovnou napsat unit test?' },
];

// Usage data — 14 days of cost
const USAGE = {
  totalCost7d: 18.42,
  totalCost30d: 142.18,
  totalTokens7d: 1840000,
  byModel: [
    { name: 'Opus 4.6',   cost: 12.04, tokens: 1100000, color: '#a78bfa' },
    { name: 'Sonnet 4.5', cost: 5.86,  tokens: 620000,  color: '#38bdf8' },
    { name: 'Haiku 4.5',  cost: 0.52,  tokens: 120000,  color: '#4ade80' },
  ],
  daily: [
    1.2, 0.8, 2.4, 3.1, 1.9, 0.4, 0.0,
    2.8, 4.2, 3.8, 2.1, 0.9, 1.6, 0.0,
  ],
  topSessions: [
    { folder: '~/actions',          host: 'hetzner-eu', cost: 5.42, tokens: 482000 },
    { folder: '~/claude-remote',    host: 'home-nas',   cost: 4.18, tokens: 380000 },
    { folder: '~/music-seeker',     host: 'hetzner-eu', cost: 2.04, tokens: 192000 },
    { folder: '~/vscode-android',   host: 'home-nas',   cost: 1.62, tokens: 142000 },
  ],
};

// helpers
const fmtCost = (c) => '$' + c.toFixed(2);
const fmtTokens = (t) => t >= 1000000 ? (t/1000000).toFixed(1)+'M' : t >= 1000 ? Math.round(t/1000)+'k' : String(t);

const activityMeta = {
  working:      { label: 'Working',  color: '#fbbf24', dot: 'dot-working' },
  ready:        { label: 'Ready',    color: '#4ade80', dot: 'dot-ready' },
  approval:     { label: 'Approval', color: '#fb923c', dot: 'dot-approval' },
  idle:         { label: 'Idle',     color: '#94a3b8', dot: 'dot-idle' },
  disconnected: { label: 'Offline',  color: '#f87171', dot: 'dot-disconnected' },
};

Object.assign(window, {
  SERVERS, SESSIONS, TMUX_SESSIONS, MODES, MODELS,
  COMMANDS, TEMPLATES, HISTORY, TRANSCRIPT, USAGE,
  fmtCost, fmtTokens, activityMeta,
});
