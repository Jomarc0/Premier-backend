from pathlib import Path
import re,json,hashlib
root=Path('C:/Users/Jomar/Premier');stage=Path('target/client-lint-fixes');entries={};texts={}
def read(n):return texts.get(n,(root/n).read_text(encoding='utf-8') if (root/n).exists() else '')
def write(n,s):
    p=root/n;entries[n]={'file':n,'originalSha256':hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None};texts[n]=s
def hoist(s,name):
    pattern=r'    const '+name+r' = (async )?\(\) => \{(.*?)\n    \};'
    return re.sub(pattern,lambda m:'    '+(m[1] or '')+'function '+name+'() {'+m[2]+'\n    }',s,flags=re.S)
def defer_initial(s,names):
    # Defer the initial external request so StrictMode cleanup can cancel it.
    pattern=r'useEffect\(\(\) => \{(.*?)\}, (\[[^\]]*\])\);'
    def update(m):
        body=m[1]
        if 'setTimeout' in body or 'return ()' in body or not any(re.search(r'\b'+n+r'\(\);',body) for n in names):return m[0]
        body=body.strip();guard=''
        g=re.match(r'(if \(auth.loading\) return;)\s*',body)
        if g:guard=g[1]+'\n        ';body=body[g.end():]
        return 'useEffect(() => {\n        '+guard+'const initial = window.setTimeout(() => { '+body+' }, 0);\n        return () => window.clearTimeout(initial);\n    }, '+m[2]+');'
    return re.sub(pattern,update,s,flags=re.S)
for page,names in {
 'ActivityLogsPage':['fetchData'], 'AllUsersPage':['fetchData'], 'TransactionsPage':['fetchData'],
 'ManageAdminsPage':['fetchAdmins','fetchCashCards'], 'VehiclesPage':['fetchVehicles'],
 'AdminSecurityPage':['loadSetup'],'StaffPage':['loadCollections'],'DriverPage':['fetchDrivers']}.items():
    n=f'admin/src/pages/{page}.jsx';s=read(n)
    for name in names:s=hoist(s,name)
    s=defer_initial(s,names)
    s=re.sub(r'catch \((err|_)\) \{(\s*toast\.(?:error|info)\([^;]+;\s*)\}',lambda m:'catch {'+m[2]+'}' if not re.search(r'\b'+m[1]+r'\b',m[2]) else m[0],s)
    if page=='ManageAdminsPage':s=s.replace("import { useAdminAuth } from '../context/AdminAuthContext';\n",'').replace('    const { isSuperAdmin } = useAdminAuth();\n','')
    write(n,s)
n='admin/src/context/AdminAuthContext.jsx';s=read(n)
for name in ['clearSession','restoreSession']:s=hoist(s,name)
s=defer_initial(s,['restoreSession'])
s=s.replace('catch (err) {\n            clearSession();\n        }','catch {\n            clearSession();\n        }')
# The request interceptor always reads the current tab credential; stale defaults are redundant.
s=re.sub(r"\s*adminAPI.defaults.headers.common\['Authorization'\] =\s*`Bearer \$\{token\}`;",'',s)
s=s.replace("        delete adminAPI.defaults.headers.common['Authorization'];\n",'').replace("import adminAPI from '../api/adminAxios';\n",'')
write(n,s)

for app,file,context,hook,state in [
 ('admin','AdminAuthContext','AdminAuthContext','useAdminAuth','AdminAuthState'),
 ('admin','RealtimeContext','RealtimeContext','useRealtime','RealtimeState'),
 ('driver','DriverContext','DriverContext','useDriver','DriverAuthState')]:
    n=f'{app}/src/context/{file}.jsx';s=read(n)
    declaration=re.search(r'(?:export )?const '+context+r' = createContext\([^\n]+',s)[0]
    hookcode=s[s.index('export const '+hook):]
    hookcode=hookcode.replace('export default DriverContext;','').strip()
    s=s[:s.index('export const '+hook)]
    s=s.replace(declaration,'').replace('createContext, ','').replace('useContext, ','')
    s="import { "+context+" } from './"+state+"';\n"+s
    write(n,s)
    write(f'{app}/src/context/{state}.js',"import { createContext, useContext } from 'react';\n"+ ('export '+declaration if not declaration.startswith('export ') else declaration)+'\n'+hookcode+'\n')
    for p in (root/app/'src').rglob('*'):
        if p.suffix not in ['.js','.jsx']:continue
        pn=p.relative_to(root).as_posix();content=read(pn)
        def imports(m):
            names=[x.strip() for x in m[1].split(',')];moved=[x for x in names if x in [context,hook]];kept=[x for x in names if x not in moved]
            if not moved:return m[0]
            prefix=m[2].rsplit('/',1)[0]
            return ("import { "+', '.join(kept)+" } from '"+m[2]+"';\n" if kept else '')+"import { "+', '.join(moved)+" } from '"+prefix+'/'+state+"';"
        changed=re.sub(r"import \{ ([^}]+) \} from '([^']*/"+file+r")';",imports,content)
        if changed!=content:write(pn,changed)
n='driver/src/pages/DashboardPage.jsx';s=read(n)
s=s.replace('        fetchShiftInfo();\n        startPolling();\n        return () => stopPolling();','        const initial = setTimeout(() => fetchShiftInfo(), 0);\n        startPolling();\n        return () => { clearTimeout(initial); stopPolling(); };')
s=s.replace('catch (_) {}','catch { /* Tracking cleanup must not prevent the end-shift request. */ }')
write(n,s)
n='driver/src/hooks/useGpsTracking.js';s=read(n)
s=s.replace('            setGpsStatus(GPS_STATUS.IDLE);\n','').replace('            setGpsStatus(GPS_STATUS.DISABLED);\n','')
# Absence of a configured vehicle/browser capability is derived, not copied into effect state.
s=s.replace('return { gpsStatus,',"return { gpsStatus: !plateNumber ? GPS_STATUS.IDLE : !navigator.geolocation ? GPS_STATUS.DISABLED : gpsStatus,")
write(n,s)
write('users/eslint.config.js',read('admin/eslint.config.js'))
for n,s in texts.items():
    p=stage/'files'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
(stage/'manifest.json').write_text(json.dumps(list(entries.values()),indent=2),encoding='utf-8')
print('Staged',len(entries),'lint/lifecycle fixes without disabling lint rules.')
