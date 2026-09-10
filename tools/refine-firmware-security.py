from pathlib import Path
import re
p=Path('target/firmware-source/premier/premier.ino')
s=p.read_text(encoding='utf-8')
def replace(a,b):
    global s
    assert a in s,a[:90]
    s=s.replace(a,b)
def function(name,new):
    global s
    start=s.index(name); end=s.index('\n}',s.index('{',start))+2
    s=s[:start]+new.strip()+s[end:]
config=Path('target/firmware-source/premier/secrets.h').read_text(encoding='utf-8')
config='\n'.join(line for line in config.splitlines() if not re.match(r'#define (DEFAULT_TERMINAL_ID|DEVICE_TOKEN|PLATE_NUMBER)\b',line))
config='// Public backend hostname and CA only. Device credentials are provisioned into NVS.\n'+config[config.index('#define DEFAULT_BACKEND_URL'):]
Path('target/firmware-source/premier/terminal_config.h').write_text('#pragma once\n'+config+'\n',encoding='utf-8')
replace('#include "secrets.h"','#include "terminal_config.h"')
replace('String terminalId = DEFAULT_TERMINAL_ID;', '''String terminalId = "";
String deviceToken = "";
String provisionedPlate = "";
bool maintenanceWindow = false;
String maintenancePin = "";
bool cardRemovalRequired = false;
unsigned long cardAbsentSince = 0;
unsigned long lastHeartbeatAt = 0;
unsigned long terminalReboots = 0;
bool maintenanceAuthorized() { return maintenanceWindow && millis() < 120000 && maintenancePin.length() == 32; }
''')
s=s.replace('DEVICE_TOKEN','deviceToken').replace('PLATE_NUMBER','provisionedPlate')
function('void loadTerminalConfiguration()',r'''
void loadTerminalConfiguration() {
  backendUrl = DEFAULT_BACKEND_URL;
  terminalId = ""; deviceToken = ""; provisionedPlate = "";
  if (preferences.begin("premier", true)) {
    if (preferences.getBool("configured", false)) {
      terminalId = preferences.getString("terminal_id", "");
      deviceToken = preferences.getString("device_token", "");
      provisionedPlate = preferences.getString("plate", "");
    }
    terminalReboots = preferences.getULong("reboots", 0);
    preferences.end();
  }
}''')
replace('  manager.setAPCallback(onWifiPortalStarted);','  manager.setEnableConfigPortal(maintenanceAuthorized());\n  manager.setAPCallback(onWifiPortalStarted);')
replace('  if (eraseWifiCredentials) {','  if ((forcePortal || eraseWifiCredentials) && !maintenanceAuthorized()) return false;\n  if (eraseWifiCredentials) {')
replace('manager.startConfigPortal(WIFI_SETUP_AP_NAME)','manager.startConfigPortal(WIFI_SETUP_AP_NAME, maintenancePin.c_str())')
replace('manager.autoConnect(WIFI_SETUP_AP_NAME)','manager.autoConnect(WIFI_SETUP_AP_NAME, maintenancePin.c_str())')
replace('const unsigned long WIFI_PORTAL_TIMEOUT_SECONDS = 300;', 'const unsigned long WIFI_PORTAL_TIMEOUT_SECONDS = 120;')
replace('  client.setHandshakeTimeout(30);','  if (backendUrl != DEFAULT_BACKEND_URL || !path.startsWith("/api/") || deviceToken.length() < 32) return false;\n  http.setConnectTimeout(5000);\n  client.setHandshakeTimeout(8);')
function('void pollMaintenanceCommands()',r'''
void pollMaintenanceCommands() {
  static String command = ""; static bool overflow = false;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\r' || c == '\n') {
      if (!overflow && maintenanceAuthorized() && command.startsWith("AUTH " + maintenancePin + " ")) {
        String action = command.substring(38); action.trim();
        if (action == "PRINT_TEST") printPrinterTestReceipt();
        else if (action == "WIFI_CONFIG") configureWifi(true, false);
        else if (action == "WIFI_RESET") configureWifi(true, true);
        else if (action.startsWith("PROVISION ")) {
          JsonDocument data;
          if (!deserializeJson(data, action.substring(10))) {
            String id = data["deviceId"] | "", token = data["token"] | "", plate = data["plate"] | "";
            bool valid = id.length() > 0 && id.length() <= 80 && token.length() >= 32 && token.length() <= 256 && plate.length() > 0 && plate.length() <= 40;
            for (size_t i=0;i<id.length();i++) valid = valid && (isalnum((unsigned char)id[i]) || id[i]=='-' || id[i]=='_');
            if (valid && preferences.begin("premier", false)) {
              bool saved = preferences.putBool("configured", false) == 1;
              saved = saved && preferences.putString("terminal_id", id) > 0 && preferences.putString("device_token", token) > 0 && preferences.putString("plate", plate) > 0;
              saved = saved && preferences.getString("terminal_id", "") == id && preferences.getString("device_token", "") == token && preferences.getString("plate", "") == plate;
              if (saved) saved = preferences.putBool("configured", true) == 1;
              preferences.end(); loadTerminalConfiguration();
              Serial.println(saved ? "Provisioning committed." : "Provisioning failed; terminal remains unavailable.");
            }
          }
        }
      }
      command = ""; overflow = false;
    } else if (!overflow && command.length() < 1024) command += c;
    else { command = ""; overflow = true; }
  }
}''')
replace('  Serial.begin(115200);',r'''
  Serial.begin(115200);
  pinMode(0, INPUT_PULLUP);
  maintenanceWindow = digitalRead(0) == LOW;
  if (maintenanceWindow) {
    char pin[33]; snprintf(pin, sizeof(pin), "%08lx%08lx%08lx%08lx", (unsigned long)esp_random(), (unsigned long)esp_random(), (unsigned long)esp_random(), (unsigned long)esp_random());
    maintenancePin = pin;
    // Display only to the physically present maintenance operator. Never forward this to telemetry.
    Serial.println("Physical maintenance window (120s). One-time authorization: " + maintenancePin);
  }
  if (preferences.begin("premier", false)) {
    terminalReboots = preferences.getULong("reboots", 0) + 1;
    preferences.putULong("reboots", terminalReboots); preferences.end();
  }
''')
replace('  OfflineFareTransaction tx = createFareTransaction(method, credential);',
        '  if (terminalId.length() == 0 || deviceToken.length() < 32 || provisionedPlate.length() == 0) { showStorageFailure(); return; }\n  OfflineFareTransaction tx = createFareTransaction(method, credential);')
# NMEA UTC date/time prevents buffered stale UART fixes from being restamped at receipt.
pos=s.index('bool postGpsUpdate(')
s=s[:pos]+r'''
String gpsCaptureTimestamp() {
  if (!gps.date.isValid() || !gps.time.isValid() || gps.time.age() > 45000 || !gps.location.isValid() || gps.location.age() > 45000) return "";
  char stamp[25]; snprintf(stamp,sizeof(stamp),"%04d-%02d-%02dT%02d:%02d:%02dZ",gps.date.year(),gps.date.month(),gps.date.day(),gps.time.hour(),gps.time.minute(),gps.time.second());
  return String(stamp);
}
''' +s[pos:]
start=s.index('  String body = "{\\"requestNonce',s.index('bool postGpsUpdate('))
end=s.index('\n',start)
s=s[:start]+r'''
  String captured = gpsCaptureTimestamp();
  bool validFix = captured.length() > 0;
  JsonDocument bodyJson;
  bodyJson["requestNonce"] = nonce; bodyJson["requestTimestamp"] = timestamp; bodyJson["plateNumber"] = provisionedPlate;
  bodyJson["fixValid"] = validFix;
  if (validFix) { bodyJson["capturedAt"] = captured; bodyJson["latitude"] = latitude; bodyJson["longitude"] = longitude; }
  if (gps.satellites.isValid()) bodyJson["satellites"] = gps.satellites.value();
  if (gps.hdop.isValid()) bodyJson["hdop"] = gps.hdop.hdop();
  bodyJson["speed"] = speedKmph; bodyJson["heading"] = headingDegrees;
  String body; serializeJson(bodyJson, body);
''' +s[end:]
function('void pollGps()',r'''
void pollGps() {
  while (gpsSerial.available()) gps.encode(gpsSerial.read());
  if (millis() - lastGpsPostAt < GPS_POST_INTERVAL_MS) return;
  lastGpsPostAt = millis();
  postGpsUpdate(gps.location.isValid() ? gps.location.lat() : 0.0, gps.location.isValid() ? gps.location.lng() : 0.0);
}''')
# A held card cannot produce a new intent merely because a cooldown expired.
replace('  if (!success) {\n    // Physical cards', '  if (!success) {\n    if (!cardAbsentSince) cardAbsentSince = millis();\n    if (millis() - cardAbsentSince > 1000) cardRemovalRequired = false;\n    // Physical cards')
replace('  String uidText = formatUid(uid, uidLength);\n\n  Serial.println();',
        '  cardAbsentSince = 0;\n  if (cardRemovalRequired) return;\n  cardRemovalRequired = true;\n  String uidText = formatUid(uid, uidLength);\n\n  Serial.println();')
replace('jsonNumber(response, "cashReceived", result.deductedFare)', 'jsonNumber(response, "cashReceived", "--")')
replace('jsonNumber(response, "change", "0.00")', 'jsonNumber(response, "change", "--")')
replace('  Serial.println("BACKEND UID CAPTURE MESSAGE: " + jsonString(response, "message", shortResponse(response)));','  Serial.println("[CAPTURE] Response received.");')
replace('  Serial.println("[GPS] Backend message: " + jsonString(response, "message", shortResponse(response)));','  Serial.println("[GPS] Response received.");')
# Bounded health, no claims about paper, cover, or scanner self-test without hardware sensing.
pos=s.index('void loop()')
s=s[:pos]+r'''
void postDeviceHeartbeat() {
  if (millis() - lastHeartbeatAt < 30000 || offlineSyncTaskRunning || WiFi.status() != WL_CONNECTED) return;
  lastHeartbeatAt = millis();
  JsonDocument report;
  report["firmwareVersion"] = FIRMWARE_VERSION; report["buildVersion"] = "remediation-20260903";
  report["rssi"] = WiFi.RSSI(); report["uptimeSeconds"] = millis()/1000; report["minimumFreeHeap"] = ESP.getMinFreeHeap();
  report["rebootCount"] = terminalReboots; report["rebootReason"] = String((int)esp_reset_reason());
  report["queueDepth"] = getPendingTransactionCount(); report["journalWrites"] = journalWrites;
  if (gps.location.isValid()) report["gpsAgeSeconds"] = gps.location.age()/1000;
  if (gps.satellites.isValid()) report["satellites"] = gps.satellites.value();
  report["nfc"] = rfidConnected ? "READY" : "UNAVAILABLE"; report["scanner"] = "UNKNOWN"; report["printer"] = "UNKNOWN";
  String body; serializeJson(report,body);
  HTTPClient http; WiFiClientSecure client;
  if (!beginSecureHttp(http,client,"/api/rfid/heartbeat")) return;
  http.addHeader("Content-Type","application/json"); http.addHeader("X-Device-Id",terminalId); http.addHeader("X-Device-Token",deviceToken);
  http.setTimeout(8000); http.POST(body); http.end();
}
''' +s[pos:]
replace('  pollGps();\n  scheduleOfflineSynchronization();','  pollGps();\n  postDeviceHeartbeat();\n  scheduleOfflineSynchronization();')
p.write_text(s,encoding='utf-8',newline='\n')
print('Staging firmware now uses provisioned NVS credentials, authenticated physical maintenance, NMEA capture time, truthful heartbeat and removal latch.')
