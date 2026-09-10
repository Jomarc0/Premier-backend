"""Stage narrow React dependency fixes against recorded source hashes."""
from pathlib import Path
import hashlib
import json
import re

root = Path('C:/Users/Jomar/Premier')
stage = Path('target/admin-effect-dependencies')
manifest = []

def save(relative, original, changed):
    assert original != changed, relative
    target = stage / 'files' / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(changed, encoding='utf-8')
    manifest.append({'file': relative, 'originalSha256': hashlib.sha256((root / relative).read_bytes()).hexdigest()})

def callback(source, name, dependencies, move=False):
    pattern = rf'    (async )?function {name}\(\) \{{\n.*?\n    \}}'
    match = re.search(pattern, source, re.S)
    assert match, name
    block = match.group()
    block = re.sub(rf'(async )?function {name}\(\)', lambda m: f'const {name} = useCallback({m[1] or ""}() =>', block, count=1)
    block = block[:-1] + '}, [' + dependencies + ']);'
    source = source[:match.start()] + source[match.end():]
    insertion = source.index('    useEffect(') if move else match.start()
    return source[:insertion] + block + '\n\n' + source[insertion:]

relative = 'admin/src/context/AdminAuthContext.jsx'
original = (root / relative).read_text(encoding='utf-8')
source = original.replace('useState, useEffect', 'useState, useEffect, useCallback')
source = callback(source, 'clearSession', '', True)
source = callback(source, 'restoreSession', 'clearSession', True)
source = source.replace('return () => window.clearTimeout(initial);\n    }, []);', 'return () => window.clearTimeout(initial);\n    }, [restoreSession]);', 1)
source = source.replace('const logout = () => {', 'const logout = useCallback(() => {').replace("window.location.href = '/admin/login';\n    };", "window.location.href = '/admin/login';\n    }, [clearSession]);")
source = source.replace('const setTwoFactorEnabled = (enabled) => {', 'const setTwoFactorEnabled = useCallback((enabled) => {').replace('setTwoFactorEnabledState(nextValue);\n    };', 'setTwoFactorEnabledState(nextValue);\n    }, []);')
save(relative, original, source)

for page, name, dependencies, effect_old, effect_new in [
    ('ActivityLogsPage', 'fetchData', 'page, logout', '[page, auth.loading]', '[fetchData, auth.loading]'),
    ('AllUsersPage', 'fetchData', 'page', '[page]', '[fetchData]'),
    ('TransactionsPage', 'fetchData', 'page, staffCashDate', '[page, staffCashDate]', '[fetchData]'),
    ('VehiclesPage', 'fetchVehicles', 'logout', '[auth.loading]', '[auth.loading, fetchVehicles]'),
    ('StaffPage', 'loadCollections', 'date', '[date]', '[loadCollections]'),
    ('AdminSecurityPage', 'loadSetup', 'setTwoFactorEnabled', '[]', '[loadSetup]'),
]:
    relative = f'admin/src/pages/{page}.jsx'
    original = (root / relative).read_text(encoding='utf-8')
    source = original.replace('useEffect,', 'useCallback, useEffect,', 1)
    if 'logout' in dependencies:
        source = source.replace('const auth = useAdminAuth();', 'const auth = useAdminAuth();\n    const { logout } = auth;').replace('auth.logout();', 'logout();')
    source = callback(source, name, dependencies, True)
    source = source.replace('return () => window.clearTimeout(initial);\n    }, ' + effect_old + ');', 'return () => window.clearTimeout(initial);\n    }, ' + effect_new + ');', 1)
    source = source.replace('}), [subscribe, page]);', '}), [subscribe, fetchData]);')
    if page == 'VehiclesPage':
        source = source.replace('}), [subscribe]);', '}), [subscribe, fetchVehicles]);')
        source = source.replace("            console.error('Vehicles fetch error:', err);\n", '')
    save(relative, original, source)

relative = 'admin/src/components/AdminSidebar.jsx'
original = (root / relative).read_text(encoding='utf-8')
source = original.replace('const AdminSidebar = () => {', 'const noop = () => {};\n\nconst AdminSidebar = () => {')
source = source.replace('auth?.setTwoFactorEnabled || (() => {})', 'auth?.setTwoFactorEnabled || noop')
source = source.replace('[admin?.id, admin?.role, twoFactorEnabled]', '[admin, twoFactorEnabled, setTwoFactorEnabled]')
save(relative, original, source)
(stage / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(f'Staged {len(manifest)} files.')
