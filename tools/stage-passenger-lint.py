from pathlib import Path
import re,json,hashlib
root=Path('C:/Users/Jomar/Premier');stage=Path('target/passenger-lint-fixes');texts={};entries={}
def read(n):return texts.get(n,(root/n).read_text(encoding='utf-8') if (root/n).exists() else '')
def write(n,s):
 p=root/n;entries[n]={'file':n,'originalSha256':hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None};texts[n]=s
for file,context,hook,state in [('AuthContext','AuthContext','useAuth','AuthState'),('RealtimeContext','RealtimeContext','useRealtime','RealtimeState')]:
 n=f'users/src/context/{file}.jsx';s=read(n)
 declaration=re.search(r'const '+context+r' = createContext\([^\n]+',s)[0]
 hookcode=s[s.index('export const '+hook):].strip();s=s[:s.index('export const '+hook)]
 s=s.replace(declaration,'').replace('createContext, ','').replace('useContext, ','')
 s="import { "+context+" } from './"+state+"';\n"+s
 if file=='AuthContext':s=s.replace('catch (error)', 'catch')
 write(n,s);write(f'users/src/context/{state}.js',"import { createContext, useContext } from 'react';\nexport "+declaration+'\n'+hookcode+'\n')
 for p in (root/'users/src').rglob('*'):
  if p.suffix not in ['.js','.jsx']:continue
  pn=p.relative_to(root).as_posix();content=read(pn)
  def imports(m):
   names=[x.strip() for x in m[1].split(',')];moved=[x for x in names if x in [context,hook]];kept=[x for x in names if x not in moved]
   if not moved:return m[0]
   prefix=m[2].rsplit('/',1)[0]
   return ("import { "+', '.join(kept)+" } from '"+m[2]+"';\n" if kept else '')+"import { "+', '.join(moved)+" } from '"+prefix+'/'+state+"';"
  changed=re.sub(r"import \{ ([^}]+) \} from '([^']*/"+file+r")';",imports,content)
  if changed!=content:write(pn,changed)
n='users/src/api/chatbotApi.js';write(n,read(n).replace("import axios from 'axios';\n",''))
n='users/src/components/Navbar.jsx';s=read(n).replace("  const passengerName = passenger?.name || 'Maria';\n",'')
s=s.replace('const { passenger, logout }', 'const { logout }');write(n,s)
n='users/src/pages/TotpSetupPage.jsx';write(n,read(n).replace("({ accountType = 'passenger' })",'()'))
n='users/src/pages/DashboardPage.jsx';s=read(n).replace('\ufeff','')
s=re.sub(r'catch \(err\) \{(\s*toast.error\([^;]+;\s*)\}',r'catch {\1}',s);write(n,s)
write('users/src/hooks/useNotifications.js','''import { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import { requestNotificationPermission, onForegroundMessage } from '../firebase';
import API from '../api/axiosConfig';

export default function useNotifications() {
  const [fcmToken, setFcmToken] = useState(null);
  const [permission, setPermission] = useState(() => typeof Notification === 'undefined' ? 'unsupported' : Notification.permission);
  // Permission prompts must follow an explicit user gesture, never an effect on mount.
  const enableNotifications = useCallback(async () => {
    const token = await requestNotificationPermission();
    if (!token) return false;
    try {
      await API.put('/notifications/fcm-token', { fcmToken: token });
      setFcmToken(token); setPermission('granted'); return true;
    } catch { toast.error('Unable to enable notifications. Please try again.'); return false; }
  }, []);
  useEffect(() => onForegroundMessage(() => {
    toast.info('There is an update to your Premier account. Open your history for details.');
  }), []);
  return { fcmToken, permission, enableNotifications };
}
''')
for n,s in texts.items():
 p=stage/'files'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
(stage/'manifest.json').write_text(json.dumps(list(entries.values()),indent=2),encoding='utf-8')
print('Staged',len(entries),'passenger lint/lifecycle fixes.')
