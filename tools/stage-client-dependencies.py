"""Snapshot client manifests for isolated dependency resolution and hash-guarded application."""
from pathlib import Path
import hashlib
import json

root = Path('C:/Users/Jomar/Premier')
stage = Path('target/client-dependencies')
manifest = []
for app in ('admin', 'staff', 'users', 'users-mobile', 'rfid', 'driver'):
    for name in ('package.json', 'package-lock.json'):
        relative = f'{app}/{name}'
        original = (root / relative).read_bytes()
        destination = stage / 'files' / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(original)
        manifest.append({'file': relative, 'originalSha256': hashlib.sha256(original).hexdigest()})
    if app == 'staff':
        path = stage / 'files' / app / 'package.json'
        package = json.loads(path.read_text(encoding='utf-8'))
        package['dependencies']['vite'] = '^8.2.2'
        package['dependencies']['@vitejs/plugin-react'] = '^6.0.1'
        path.write_text(json.dumps(package, indent=2) + '\n', encoding='utf-8')
(stage / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print('Staged 12 dependency manifests; application files are unchanged.')
