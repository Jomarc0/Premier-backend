"""Audit resolved public Maven coordinates against OSV; never uploads source or credentials."""
import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--dependencies', default='target/dependencies.txt')
parser.add_argument('--output', default='target/maven-vulnerabilities.json')
args = parser.parse_args()
coordinates = set()
for line in Path(args.dependencies).read_text(encoding='utf-8-sig').splitlines():
    line = re.sub(r'^\[INFO\]\s*', '', line.strip()).split(' -- module ')[0]
    parts = line.split(':')
    if len(parts) in (5, 6) and re.fullmatch(r'[A-Za-z0-9_.-]+', parts[0]):
        scope = parts[-1].split()[0]
        if scope in ('compile', 'runtime', 'test', 'provided', 'system'):
            coordinates.add((parts[0] + ':' + parts[1], parts[-2]))
if not coordinates:
    sys.exit('No resolved dependencies found; refusing an empty security pass.')
coordinates = sorted(coordinates)
findings = []
for start in range(0, len(coordinates), 100):
    batch = coordinates[start:start + 100]
    body = {'queries': [{'package': {'name': name, 'ecosystem': 'Maven'}, 'version': version} for name, version in batch]}
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'User-Agent': 'Premier-dependency-audit/1'})
    with urllib.request.urlopen(request, timeout=30) as response:
        results = json.load(response)['results']
    if len(results) != len(batch):
        sys.exit('Incomplete OSV response; security gate failed.')
    for (name, version), result in zip(batch, results):
        if result.get('next_page_token'):
            sys.exit('OSV results require pagination; refusing an incomplete security pass.')
        if result.get('vulns'):
            findings.append({'package': name, 'version': version, 'vulnerabilities': result['vulns']})
report = {'provider': 'https://osv.dev', 'resolvedPackages': len(coordinates), 'findings': findings}
destination = Path(args.output)
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(f'OSV audited {len(coordinates)} resolved Maven packages; {len(findings)} packages have reported vulnerabilities.')
for finding in findings:
    print(finding['package'], finding['version'], ', '.join(v['id'] for v in finding['vulnerabilities']))
sys.exit(1 if findings else 0)
