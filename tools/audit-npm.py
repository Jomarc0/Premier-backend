"""Audit exact lockfile versions through OSV; transmits public package coordinates only."""
import argparse
import datetime as dt
import json
import sys
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--root', required=True)
parser.add_argument('--output', default='target/npm-osv-vulnerabilities.json')
parser.add_argument('--allowlist', default='security/npm-osv-allowlist.json')
args = parser.parse_args()
today = dt.date.today()

def load_allowlist(path):
    source = Path(path)
    if not source.exists():
        return []
    entries = json.loads(source.read_text(encoding='utf-8'))
    if not isinstance(entries, list):
        sys.exit(f'{path}: expected a JSON array of allowlist entries.')
    parsed = []
    for index, entry in enumerate(entries, start=1):
        for key in ('package', 'version', 'advisory', 'apps', 'expiresOn', 'reason'):
            if key not in entry:
                sys.exit(f'{path}: entry {index} is missing {key}.')
        try:
            expires_on = dt.date.fromisoformat(entry['expiresOn'])
        except ValueError:
            sys.exit(f'{path}: entry {index} has invalid expiresOn.')
        if expires_on < today:
            sys.exit(f'{path}: entry {index} expired on {entry["expiresOn"]}.')
        parsed.append({**entry, 'apps': set(entry['apps']), 'expiresOnDate': expires_on})
    return parsed

allowlist = load_allowlist(args.allowlist)

def allowed_for(finding, vulnerability_id):
    finding_apps = set(finding['apps'])
    for entry in allowlist:
        if entry['package'] != finding['package'] or entry['version'] != finding['version'] or entry['advisory'] != vulnerability_id:
            continue
        if entry['apps'] != finding_apps:
            continue
        return entry
    return None

packages = {}
counts = {}
for app in ('admin', 'staff', 'users', 'users-mobile', 'rfid', 'driver'):
    lock = json.loads((Path(args.root) / app / 'package-lock.json').read_text(encoding='utf-8-sig'))
    if lock.get('lockfileVersion', 0) < 2:
        sys.exit(f'{app}: unsupported lockfile format; audit refused.')
    found = set()
    for path, entry in lock['packages'].items():
        if not path or entry.get('link'):
            continue
        name = entry.get('name') or path.rsplit('node_modules/', 1)[-1]
        version = entry.get('version')
        if not version:
            sys.exit(f'{app}: unresolved version for {name}; audit refused.')
        coordinate = (name, version)
        found.add(coordinate)
        packages.setdefault(coordinate, set()).add(app)
    if not found:
        sys.exit(f'{app}: empty dependency audit refused.')
    counts[app] = len(found)
coordinates = sorted(packages)
findings = []
for start in range(0, len(coordinates), 100):
    batch = coordinates[start:start + 100]
    payload = {'queries': [{'package': {'name': name, 'ecosystem': 'npm'}, 'version': version} for name, version in batch]}
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json', 'User-Agent': 'Premier-lockfile-audit/1'})
    with urllib.request.urlopen(request, timeout=30) as response:
        results = json.load(response)['results']
    if len(results) != len(batch) or any(r.get('next_page_token') for r in results):
        sys.exit('Incomplete OSV result; audit failed.')
    for coordinate, result in zip(batch, results):
        if result.get('vulns'):
            findings.append({'package': coordinate[0], 'version': coordinate[1],
                'apps': sorted(packages[coordinate]), 'vulnerabilities': result['vulns']})
open_findings = []
accepted_findings = []
for finding in findings:
    open_vulnerabilities = []
    accepted_vulnerabilities = []
    for vulnerability in finding['vulnerabilities']:
        entry = allowed_for(finding, vulnerability['id'])
        if entry:
            accepted_vulnerabilities.append({'id': vulnerability['id'], 'expiresOn': entry['expiresOn'],
                'reason': entry['reason']})
        else:
            open_vulnerabilities.append(vulnerability)
    if open_vulnerabilities:
        open_findings.append({**finding, 'vulnerabilities': open_vulnerabilities})
    if accepted_vulnerabilities:
        accepted_findings.append({k: finding[k] for k in ('package', 'version', 'apps')} | {
            'vulnerabilities': accepted_vulnerabilities})
report = {'provider': 'https://osv.dev', 'uniquePackageVersions': len(coordinates), 'appPackageCounts': counts,
    'findings': open_findings, 'acceptedFindings': accepted_findings}
destination = Path(args.output)
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(f'OSV audited {len(coordinates)} unique locked npm package versions across six apps; {len(open_findings)} have unaccepted advisories; {len(accepted_findings)} are allowlisted.')
for finding in open_findings:
    print(finding['package'], finding['version'], ','.join(finding['apps']), ','.join(v['id'] for v in finding['vulnerabilities']))
for finding in accepted_findings:
    print('ALLOWLISTED', finding['package'], finding['version'], ','.join(finding['apps']), ','.join(v['id'] for v in finding['vulnerabilities']))
sys.exit(1 if open_findings else 0)
