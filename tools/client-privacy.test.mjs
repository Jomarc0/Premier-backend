import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const root = 'C:/Users/Jomar/Premier/';
async function moduleFrom(path) {
  const source = await readFile(root + path, 'utf8');
  return import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
}
for (const app of ['admin', 'users', 'users-mobile']) {
  test(`${app}: spreadsheet formulas and quoted delimiters stay literal`, async () => {
    const { csvEscape } = await moduleFrom(`${app}/src/lib/csv.js`);
    for (const payload of ['=HYPERLINK("https://example.invalid")', '+1+1', '-1+1', '@SUM(A1)', '  =1+1', '\t=1+1', '\rpayload', '\nvalue', '\uFEFF=1']) {
      assert.ok(csvEscape(payload).startsWith('"\''), payload);
    }
    assert.equal(csvEscape('normal,"label"'), '"normal,""label"""');
    assert.equal(csvEscape(null), '""');
    assert.equal(csvEscape(12.34), '"12.34"');
  });
}
function storage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return { get length() { return values.size; }, key: index => [...values.keys()][index],
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) };
}
test('passenger cleanup removes private caches while preserving privacy preferences', async () => {
  const { clearPrivateStorage } = await moduleFrom('users/src/lib/authStorage.js');
  const data = storage({ token: 'synthetic', tempToken: 'synthetic', premier_chat_history_12: 'private',
    'premier:passenger-notifications:12': 'private', premier_privacy_notice_accepted: 'true', theme: 'dark' });
  clearPrivateStorage(data);
  assert.equal(data.length, 2);
  assert.equal(data.getItem('premier_privacy_notice_accepted'), 'true');
  assert.equal(data.getItem('theme'), 'dark');
});
test('telemetry expires while the page is open and heartbeat does not freshen GPS', async () => {
  const { monitoringView, queueView, formatSpeed } = await moduleFrom('admin/src/lib/telemetry.js');
  const now = Date.now();
  const bus = { deviceStatus: 'ONLINE', deviceLastSeen: new Date(now).toISOString(),
    capturedAt: new Date(now).toISOString(), locationFresh: true, gpsState: 'GPS_VALID', speed: 20 };
  assert.equal(monitoringView(bus, now).locationFresh, true);
  const expired = monitoringView(bus, now + 46000);
  assert.equal(expired.online, true); assert.equal(expired.locationFresh, false); assert.equal(expired.speed, null);
  assert.equal(monitoringView({ ...bus, gpsState: 'GPS_NO_FIX' }, now).locationFresh, false);
  assert.equal(monitoringView(bus, now + 91000).online, false);
  assert.equal(formatSpeed(null), 'Unknown'); assert.equal(formatSpeed(0), '0.0 km/h');
  assert.equal(queueView({ capturedAt: bus.capturedAt, estimatedArrivalMinutes: 3 }, now + 46000).estimatedArrivalMinutes, null);
});
test('stale hidden passenger tab expires before accepting new activity', async () => {
  globalThis.localStorage = storage({ token: 'legacy', theme: 'dark' });
  globalThis.sessionStorage = storage({ token: 'current', users_last_activity: String(Date.now() - 31 * 60000) });
  const listeners = new Map(); let redirect;
  globalThis.window = { addEventListener: (name, listener) => listeners.set(name, listener) };
  globalThis.document = { addEventListener: (name, listener) => listeners.set(name, listener) };
  globalThis.location = { replace: path => { redirect = path; } };
  const interval = globalThis.setInterval;
  globalThis.setInterval = () => 0;
  try {
    const { installSessionGuard } = await moduleFrom('users/src/lib/sessionGuard.js');
    installSessionGuard();
    assert.equal(redirect, '/login'); assert.equal(sessionStorage.getItem('token'), null);
    listeners.get('pointerdown')();
    assert.equal(sessionStorage.getItem('token'), null);
    assert.equal(localStorage.getItem('token'), null); assert.equal(localStorage.getItem('theme'), 'dark');
  } finally { globalThis.setInterval = interval; }
});
