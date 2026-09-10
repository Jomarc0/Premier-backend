import hashlib, json, re
from pathlib import Path
root=Path('C:/Users/Jomar/Premier')
stage=Path('target/browser-privacy')
entries={}
def read(n): return (root/n).read_text(encoding='utf-8')
def write(n,s):
    p=root/n
    entries[n]={'file':n,'originalSha256':hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None}
    target=stage/'files'/n; target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(s,encoding='utf-8',newline='\n')

csv='''// Spreadsheet programs interpret formulas even inside properly quoted CSV cells.
export function safeSpreadsheetText(value) {
  const text = value == null ? '' : String(value);
  return /^[\\s\\uFEFF]*[=+@-]|^[\\t\\r\\n]/u.test(text) ? "'" + text : text;
}
export function csvEscape(value) {
  return `"${safeSpreadsheetText(value).replace(/"/g, '""')}"`;
}
'''
for app in ['admin','users','users-mobile']:
    write(f'{app}/src/lib/csv.js',csv)
for n in ['users/src/pages/DashboardPage.jsx','users-mobile/src/screens/DashboardScreen.js']:
    s=read(n)
    s=re.sub(r'const csvEscape = \(value\) => \{.*?\n\};\n', '',s,flags=re.S)
    write(n,"import { csvEscape } from '../lib/csv';\n"+s)
n='admin/src/pages/ReportsPage.jsx'
s=read(n)
s=s.replace('row.map(cell => `"${String(cell ?? \'\').replaceAll(\'"\', \'""\')}"`)','row.map(csvEscape)')
s=s.replace('${escapeHtml(cell)}','${escapeHtml(safeSpreadsheetText(cell))}')
write(n,"import { csvEscape, safeSpreadsheetText } from '../lib/csv';\n"+s)

guard='''// Per-tab credentials; remove legacy retained sessions instead of reusing them.
const KEYS = __KEYS__;
const TOKEN_KEY = __TOKEN__;
const LAST_ACTIVITY = '__APP___last_activity';
const IDLE_MS = __TIMEOUT__;
const privateKey = key => KEYS.includes(key) || key === LAST_ACTIVITY
  || key?.startsWith('premier_chat_') || key?.startsWith('premier:passenger-notifications:');
function clear(storage) {
  const keys = [];
  for (let index = 0; index < storage.length; index++) if (privateKey(storage.key(index))) keys.push(storage.key(index));
  keys.forEach(key => storage.removeItem(key));
}
export function installSessionGuard() {
  clear(localStorage);
  let previousToken = sessionStorage.getItem(TOKEN_KEY);
  const expire = () => { clear(sessionStorage); location.replace(__LOGIN__); };
  const check = () => {
    const token = sessionStorage.getItem(TOKEN_KEY);
    if (!token) { previousToken = null; sessionStorage.removeItem(LAST_ACTIVITY); return; }
    if (token !== previousToken) { previousToken = token; sessionStorage.setItem(LAST_ACTIVITY, String(Date.now())); }
    const last = Number(sessionStorage.getItem(LAST_ACTIVITY));
    if (!last) sessionStorage.setItem(LAST_ACTIVITY, String(Date.now()));
    else if (Date.now() - last >= IDLE_MS || last > Date.now() + 60000) expire();
  };
  const touch = () => {
    // Check before accepting activity, so a hidden stale tab cannot revive a session.
    check();
    if (sessionStorage.getItem(TOKEN_KEY)) sessionStorage.setItem(LAST_ACTIVITY, String(Date.now()));
  };
  for (const event of ['pointerdown', 'keydown', 'touchstart']) window.addEventListener(event, touch, { passive: true });
  document.addEventListener('visibilitychange', check);
  setInterval(check, 30000);
  check();
}
'''
config={
 'users':(['token','tempToken','passengerName','postLoginAction'],'token','/login',30),
 'admin':(['adminToken','adminName','adminUsername','adminRole','admin2FaEnabled'],'adminToken','/admin/login',15),
 'staff':(['premier_staff_session'],'premier_staff_session','/',15),
 'driver':(['driverToken','driverInfo'],'driverToken','/login',30),
}
for app,(keys,token,login,minutes) in config.items():
    if app!='users':
        for p in (root/app/'src').rglob('*'):
            if p.suffix not in ['.js','.jsx']:continue
            n=p.relative_to(root).as_posix(); s=read(n)
            changed=s.replace('localStorage.', 'sessionStorage.')
            if changed!=s:write(n,changed)
    s=guard.replace('__KEYS__',json.dumps(keys)).replace('__TOKEN__',json.dumps(token)).replace('__APP__',app).replace('__TIMEOUT__',str(minutes*60000)).replace('__LOGIN__',json.dumps(login))
    write(f'{app}/src/lib/sessionGuard.js',s)
    n=f'{app}/src/main.jsx'; s=read(n)
    # Install before React renders; no network calls or credentials in URLs.
    index=s.rfind('\n',0,s.index('createRoot('))+1
    s=s[:index]+'installSessionGuard();\n\n'+s[index:]
    write(n,"import { installSessionGuard } from './lib/sessionGuard';\n"+s)

(stage/'manifest.json').write_text(json.dumps(list(entries.values()),indent=2),encoding='utf-8')
print(f'Staged {len(entries)} browser session / export fixes.')
