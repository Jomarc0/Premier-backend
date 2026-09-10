from pathlib import Path
import hashlib,json
root=Path('C:/Users/Jomar/Premier');stage=Path('target/telemetry-ui');entries={}
def write(n,s):
 p=root/n;entries[n]={'file':n,'originalSha256':hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None}
 target=stage/'files'/n;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(s,encoding='utf-8',newline='\n')
def read(n):return (root/n).read_text(encoding='utf-8')
helpers='''export function freshAt(value, now, lifetime = 45000) {
  if (!value) return false;
  const captured = Date.parse(value);
  return Number.isFinite(captured) && captured > now - lifetime && captured <= now + 10000;
}
export function monitoringView(bus, now) {
  const online = bus.deviceStatus === 'ONLINE' && freshAt(bus.deviceLastSeen, now, 90000);
  const fresh = online && bus.locationFresh === true && bus.gpsState === 'GPS_VALID' && freshAt(bus.capturedAt, now);
  return { ...bus, online, locationFresh: fresh, deviceStatus: online ? 'ONLINE' : 'DEVICE_OFFLINE',
    status: !online ? 'OFFLINE' : fresh ? 'ONLINE' : 'DELAYED',
    gpsState: fresh ? 'GPS_VALID' : bus.gpsState === 'GPS_VALID' ? 'GPS_STALE' : bus.gpsState || 'GPS_NO_FIX',
    gpsStatus: fresh ? bus.gpsStatus : 'Unknown', speed: fresh ? bus.speed : null };
}
export function queueView(bus, now) {
  if (freshAt(bus.capturedAt, now)) return bus;
  return { ...bus, distanceRemainingKm: null, estimatedArrivalMinutes: null,
    status: 'GPS_UNKNOWN', statusLabel: 'GPS unavailable' };
}
export function formatSpeed(value) {
  return value == null || !Number.isFinite(Number(value)) || Number(value) < 0 || Number(value) > 180
    ? 'Unknown' : `${Number(value).toFixed(1)} km/h`;
}
'''
for app in ['admin','staff']:write(f'{app}/src/lib/telemetry.js',helpers)
n='admin/src/pages/VehicleMonitoringPage.jsx';s=read(n)
s="import { monitoringView, formatSpeed } from '../lib/telemetry';\n"+s
s=s.replace("const formatSpeed = (value) => `${Number(value || 0).toFixed(1)} km/h`;\n",'')
s=s.replace('const validCoordinates = (item) => {','const validCoordinates = (item) => {\n    if (item?.latitude == null || item?.longitude == null) return false;')
s=s.replace('const formatCoordinate = (value) => {','const formatCoordinate = (value) => {\n    if (value == null) return \'N/A\';')
s=s.replace('const [buses, setBuses] = useState([]);', '''const [busRecords, setBuses] = useState([]);
    const [telemetryNow, setTelemetryNow] = useState(() => Date.now());
    useEffect(() => {
        const clock = setInterval(() => setTelemetryNow(Date.now()), 5000);
        return () => clearInterval(clock);
    }, []);
    const buses = busRecords.map(bus => monitoringView(bus, telemetryNow));''')
s=s.replace("buses.filter(bus => bus.status === 'ONLINE').length",'buses.filter(bus => bus.online).length')
s=s.replace('<span>Status: <strong>{bus.status}</strong></span>', '<span>Device: <strong>{bus.deviceStatus}</strong></span>')
s=s.replace('<span>GPS status: {bus.gpsStatus}</span>',"<span>GPS: {bus.gpsState}{!bus.locationFresh && ' — last known position only'}</span>")
s=s.replace('relativeTime(bus.lastUpdated)','relativeTime(bus.capturedAt)')
s=s.replace("{bus.status || 'OFFLINE'}</span>","{bus.deviceStatus || 'DEVICE_OFFLINE'}</span><br /><small>{bus.gpsState}</small>")
write(n,s)
n='staff/src/App.jsx';s=read(n)
s="import { queueView } from './lib/telemetry';\n"+s
s=s.replace('Number(bus.distanceRemainingKm ?? bus.distanceKm ?? 0)', '(bus.distanceRemainingKm ?? bus.distanceKm) == null ? null : Number(bus.distanceRemainingKm ?? bus.distanceKm)')
s=s.replace('Number(bus.estimatedArrivalMinutes ?? bus.etaMinutes ?? 0)', '(bus.estimatedArrivalMinutes ?? bus.etaMinutes) == null ? null : Number(bus.estimatedArrivalMinutes ?? bus.etaMinutes)')
s=s.replace('return a.distanceRemainingKm - b.distanceRemainingKm;', 'return (a.distanceRemainingKm ?? Infinity) - (b.distanceRemainingKm ?? Infinity);')
s=s.replace('return a.estimatedArrivalMinutes - b.estimatedArrivalMinutes;', 'return (a.estimatedArrivalMinutes ?? Infinity) - (b.estimatedArrivalMinutes ?? Infinity);')
s=s.replace('function formatDistance(value) {','function formatDistance(value) {\n  if (value == null) return "Unknown";')
s=s.replace('const [lastUpdated, setLastUpdated] = useState(null);','''const [lastUpdated, setLastUpdated] = useState(null);
  const [telemetryNow, setTelemetryNow] = useState(() => Date.now());
  useEffect(() => {
    const clock = setInterval(() => setTelemetryNow(Date.now()), 5000);
    return () => clearInterval(clock);
  }, []);''')
s=s.replace('const activeQueue = activeTerminal === "sm" ? queue.incomingToSmTerminal : queue.incomingToGrandTerminal;', 'const activeQueue = (activeTerminal === "sm" ? queue.incomingToSmTerminal : queue.incomingToGrandTerminal).map(bus => queueView(bus, telemetryNow));')
s=s.replace('Live bus queue is unavailable. Check if the Spring Boot backend is running.', 'Live bus queue is unavailable. Check your connection and retry.')
write(n,s)
(stage/'manifest.json').write_text(json.dumps(list(entries.values()),indent=2),encoding='utf-8')
print('Staged',len(entries),'truthful telemetry files.')
