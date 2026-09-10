import hashlib, json, re
from pathlib import Path

root = Path('C:/Users/Jomar/Premier')
stage = Path('target/recovery-ui')
entries = {}
def read(name):
    return (root/name).read_text(encoding='utf-8')
def write(name, text):
    original = root/name
    entries[name] = {'file': name, 'originalSha256': hashlib.sha256(original.read_bytes()).hexdigest() if original.exists() else None}
    target = stage/'files'/name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8', newline='\n')

web = '''import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiOrigin } from '../api/apiOrigin';

export default function MfaRecoveryForm() {
  const navigate = useNavigate();
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setError('');
    const recoveryToken = token.trim();
    setToken('');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(`${apiOrigin}/api/passenger/auth/recovery/complete`, {
        method: 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ recoveryToken }),
      });
      const result = await response.json();
      if (!response.ok || !result.data?.tempToken || !result.data?.requireSetup)
        throw new Error(result.message || 'Recovery authorization was not accepted.');
      sessionStorage.removeItem('postLoginAction');
      sessionStorage.setItem('tempToken', result.data.tempToken);
      navigate('/totp-setup', { replace: true });
    } catch (failure) {
      setError(failure.name === 'AbortError'
        ? 'The response was not received. Contact the verifying Super Admin if this one-use authorization has been consumed.'
        : failure.message || 'Unable to complete recovery.');
    } finally { clearTimeout(timer); setBusy(false); }
  }
  return <details className="mt-5 text-left">
    <summary className="cursor-pointer font-bold">Recover your authenticator</summary>
    <p className="my-3 text-sm">An authorized Super Admin must manually verify your identity under the organization’s approved identification procedure and record the reason in a support ticket. Your card number alone cannot authorize recovery.</p>
    <form onSubmit={submit} className="space-y-3">
      <label className="block text-sm">One-use recovery authorization
        <input type="password" value={token} onChange={event => setToken(event.target.value)}
          autoComplete="off" spellCheck={false} maxLength={2048} required
          className="mt-2 w-full rounded-xl border p-3 ph-no-capture" />
      </label>
      <p className="text-xs">Paste the authorization supplied directly by the verifying Super Admin. It expires after five minutes.</p>
      <button disabled={busy || !token.trim()} className="rounded-xl bg-brand-primary p-3 text-white disabled:opacity-50">{busy ? 'Verifying...' : 'Set up a new authenticator'}</button>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
    </form>
  </details>;
}
'''
write('users/src/components/MfaRecoveryForm.jsx', web)
s = read('users/src/pages/LoginPage.jsx').replace("import { useAuth } from '../context/AuthContext';", "import MfaRecoveryForm from '../components/MfaRecoveryForm';").replace('  const { login } = useAuth();\n','')
s=s.replace('        </form>','        </form>\n        <MfaRecoveryForm />',1)
write('users/src/pages/LoginPage.jsx',s)

admin = '''import { useEffect, useRef, useState } from 'react';
import adminAPI from '../api/adminAxios';
import { useAdminAuth } from '../context/AdminAuthContext';

export default function PassengerMfaRecovery({ ticket }) {
  const { isSuperAdmin } = useAdminAuth();
  const [verified, setVerified] = useState(false);
  const [reason, setReason] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const mounted = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    if (!result) return undefined;
    const timer = setTimeout(() => setResult(null), Math.max(0, Date.parse(result.expiresAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [result]);
  if (!isSuperAdmin()) return null;
  async function authorize(event) {
    event.preventDefault();
    if (busy || !verified || reason.trim().length < 10) return;
    setBusy(true); setError(''); setResult(null);
    try {
      const response = await adminAPI.post(`/support-tickets/${ticket.id}/authorize-mfa-recovery`, {
        identityVerified: true, reason: reason.trim(),
      }, { timeout: 20000 });
      const data = response.data?.data;
      if (!data?.recoveryToken || !Number.isFinite(Date.parse(data.expiresAt))) throw new Error('Missing recovery authorization.');
      if (mounted.current) { setResult(data); setVerified(false); setReason(''); }
    } catch (failure) {
      if (mounted.current) setError(failure.response?.data?.message || 'The authorization response was not received. Check ticket history before creating a new verified recovery ticket.');
    } finally { if (mounted.current) setBusy(false); }
  }
  return <section className="rounded-lg border border-maroon/30 p-3 ph-no-capture">
    <h3 className="font-bold">Passenger authenticator recovery</h3>
    <p className="my-2 text-sm">Verify this passenger using the organization’s approved identification procedure. A card number alone is never sufficient. Authorization immediately revokes existing sessions and authenticator access.</p>
    <form onSubmit={authorize} className="space-y-3">
      <label className="block text-sm"><input type="checkbox" checked={verified} onChange={event => setVerified(event.target.checked)} /> I completed the approved identity verification for ticket {ticket.ticketNumber}.</label>
      <label className="block text-sm">Verification reason / reference (do not enter identity-document numbers)
        <textarea value={reason} onChange={event => setReason(event.target.value)} required minLength={10} maxLength={240} className="mt-1 w-full rounded border p-2" />
      </label>
      <button disabled={busy || !verified || reason.trim().length < 10 || !!result} className="rounded bg-maroon p-2 text-white disabled:opacity-50">{busy ? 'Authorizing...' : 'Authorize one-use recovery'}</button>
    </form>
    {error && <p role="alert" className="mt-2 text-sm text-red-700">{error}</p>}
    {result && <div className="mt-3 space-y-2">
      <p className="text-sm">Deliver directly to the verified passenger. This authorization is shown only here and expires at {new Date(result.expiresAt).toLocaleTimeString()}.</p>
      <label className="block text-sm">One-use authorization<input readOnly type="password" autoComplete="off" value={result.recoveryToken} className="w-full rounded border p-2 ph-no-capture" onFocus={event => event.target.select()} /></label>
      <button type="button" onClick={() => setResult(null)} className="underline">Hide authorization</button>
    </div>}
  </section>;
}
'''
write('admin/src/components/PassengerMfaRecovery.jsx',admin)
s=read('admin/src/pages/SupportTicketsPage.jsx')
s="import PassengerMfaRecovery from '../components/PassengerMfaRecovery';\n"+s
s=s.replace('<label className={ui.fieldLabel}>Replacement card RFID UID</label>',"{!isClosed && <PassengerMfaRecovery key={selected.id} ticket={selected} />}\n                                <label className={ui.fieldLabel}>Replacement card RFID UID</label>")
s=s.replace('Enter the resolution or rejection reason for the passenger','Internal staff notes; do not enter identity-document numbers')
write('admin/src/pages/SupportTicketsPage.jsx',s)

mobile = '''import { useState } from 'react';
import { AppState, Text, TextInput, View } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import Button from './Button';
import { API_PASSENGER_BASE } from '../config';

export default function MfaRecoveryForm({ navigation }) {
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function recover() {
    if (busy || !token.trim()) return;
    setBusy(true); setError('');
    const recoveryToken = token.trim(); setToken('');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(`${API_PASSENGER_BASE}/auth/recovery/complete`, {
        method: 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ recoveryToken }),
      });
      const result = await response.json();
      if (!response.ok || !result.data?.tempToken || !result.data?.requireSetup)
        throw new Error(result.message || 'Recovery authorization was not accepted.');
      await SecureStore.setItemAsync('tempToken', result.data.tempToken);
      await SecureStore.deleteItemAsync('pendingCardNumber');
      navigation.navigate('TotpSetup');
    } catch (failure) {
      setError(failure.name === 'AbortError'
        ? 'The response was not received. Contact the verifying Super Admin if the one-use authorization has been consumed.'
        : failure.message || 'Unable to complete recovery.');
    } finally { clearTimeout(timer); setBusy(false); }
  }
  return <View style={{ marginTop: 20, gap: 12 }}>
    <Text accessibilityRole="header" style={{ fontWeight: '700' }}>Recover your authenticator</Text>
    <Text>An authorized Super Admin must verify your identity using the organization’s approved procedure and record the reason in a support ticket. A card number alone is never sufficient.</Text>
    <TextInput accessibilityLabel="One-use recovery authorization" placeholder="Paste recovery authorization"
      value={token} onChangeText={setToken} secureTextEntry autoCapitalize="none" autoCorrect={false}
      autoComplete="off" maxLength={2048} style={{ borderWidth: 1, borderRadius: 8, padding: 12 }} />
    <Text>The authorization supplied directly by the verifying Super Admin expires after five minutes.</Text>
    <Button loading={busy} disabled={!token.trim()} onPress={recover}>Set up a new authenticator</Button>
    {!!error && <Text accessibilityRole="alert" style={{ color: '#b91c1c' }}>{error}</Text>}
  </View>;
}
'''.replace('AppState, ', '')
write('users-mobile/src/components/MfaRecoveryForm.js',mobile)
s=read('users-mobile/src/screens/LoginScreen.js')
s="import MfaRecoveryForm from '../components/MfaRecoveryForm';\n"+s
s=s.replace('        <Text style={styles.privacyAcknowledgement}>','        <MfaRecoveryForm navigation={navigation} />\n\n        <Text style={styles.privacyAcknowledgement}>',1)
write('users-mobile/src/screens/LoginScreen.js',s)

# Only passenger credentials and passenger-owned caches move to tab storage.
for path in (root/'users/src').rglob('*'):
    if path.suffix not in ['.js','.jsx']: continue
    name=path.relative_to(root).as_posix()
    text=(stage/'files'/name).read_text(encoding='utf-8') if name in entries else read(name)
    changed=re.sub(r"localStorage\.(getItem|setItem|removeItem)\('(token|tempToken|passengerName|postLoginAction)'", r"sessionStorage.\1('\2'",text)
    if name.endswith('NotificationBell.jsx'): changed=changed.replace('localStorage.', 'sessionStorage.')
    if changed!=text: write(name,changed)

session='''const AUTH_KEYS = ['token', 'tempToken', 'passengerName', 'postLoginAction'];
const PRIVATE_PREFIXES = ['premier_chat_history', 'premier_chat_session', 'premier:passenger-notifications:'];
export function clearPrivateStorage(storage) {
  const remove = [];
  for (let index = 0; index < storage.length; index++) {
    const key = storage.key(index);
    if (AUTH_KEYS.includes(key) || PRIVATE_PREFIXES.some(prefix => key?.startsWith(prefix))) remove.push(key);
  }
  remove.forEach(key => storage.removeItem(key));
}
export function clearAuthStorage() {
  clearPrivateStorage(sessionStorage);
  clearPrivateStorage(localStorage);
}
// Do not silently migrate indefinitely retained credentials from older builds.
export function removeLegacyCredentials() { clearPrivateStorage(localStorage); }
'''
write('users/src/lib/authStorage.js',session)
s=(stage/'files/users/src/context/AuthContext.jsx').read_text(encoding='utf-8')
s=s.replace("import { PRIVACY_NOTICE_ACCEPTED_KEY } from '../constants/privacy';","import { clearAuthStorage } from '../lib/authStorage';")
s=re.sub(r'const clearAuthStorage = \(\) => \{.*?\n\};\n', '', s, flags=re.S)
write('users/src/context/AuthContext.jsx',s)
s=(stage/'files/users/src/api/axiosConfig.js').read_text(encoding='utf-8')
s="import { clearAuthStorage } from '../lib/authStorage';\n"+s
s=s.replace('    baseURL:', '    timeout: 20000,\n    baseURL:').replace('localStorage.clear();','clearAuthStorage();')
write('users/src/api/axiosConfig.js',s)
s=read('users/src/main.jsx')
s="import { removeLegacyCredentials } from './lib/authStorage';\n"+s
s=s.replace('initPostHog();','removeLegacyCredentials();\ninitPostHog();')
write('users/src/main.jsx',s)

# Recovery credentials must never appear in replay recordings, including remote-config activation.
for app in ['admin','users']:
    name=f'{app}/src/lib/posthog.js'
    s=read(name).replace('    autocapture: false,','    autocapture: false,\n    disable_session_recording: true,\n    mask_all_text: true,\n    mask_all_element_attributes: true,')
    write(name,s)
(stage/'manifest.json').write_text(json.dumps(list(entries.values()),indent=2),encoding='utf-8')
print(f'Staged {len(entries)} recovery/privacy files; original source hashes captured.')
