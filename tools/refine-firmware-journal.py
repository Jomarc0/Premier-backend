"""Refine the reviewed proposal in the credential-free staging sketch only."""
from pathlib import Path
p = Path('target/firmware-source/premier/premier.ino')
s = p.read_text(encoding='utf-8')
def replace(old, new):
    global s
    assert old in s, old[:100]
    s = s.replace(old, new)
def function(name, new):
    global s
    start = s.index(name)
    brace = s.index('{', start)
    # Existing C++ functions have braces inside strings/comments; use next top-level closing brace.
    end = s.index('\n}', brace) + 2
    s = s[:start] + new.strip() + s[end:]
replace('String currentImageFile = "";', 'String currentImageFile = "";\nString currentQueuePath = "";\nunsigned long journalWrites = 0;')
replace('  String offlineTransactionId;', '  String deviceId;\n  String offlineTransactionId;')
replace('  tx.offlineTransactionId = generateOfflineTransactionId();', '  tx.deviceId = terminalId;\n  tx.offlineTransactionId = generateOfflineTransactionId();')
replace('  tx.status = "PENDING_VALIDATION";', '  tx.status = "PENDING_INTENT";')
function('int countPendingTransactionsUnlocked()', r'''
int countPendingTransactionsUnlocked() {
  if (!littleFsMounted) return 0;
  File directory = LittleFS.open("/fare-pending");
  if (!directory) return 0;
  int count = 0;
  for (File file = directory.openNextFile(); file; file = directory.openNextFile()) {
    if (String(file.name()).endsWith(".json")) count++;
    file.close();
  }
  directory.close(); return count;
}''')
function('bool saveOfflineTransaction(', r'''
bool saveOfflineTransaction(const OfflineFareTransaction &tx, bool replay) {
  lastOfflineSaveWasDuplicate = false;
  if (!littleFsMounted || tx.credential.length() == 0 || tx.credential.length() > MAX_QR_PAYLOAD_BYTES
      || tx.deviceId != terminalId || !safeIntentId(tx.offlineTransactionId)) return false;
  if (!replay && tx.credential == lastQueuedCredential && millis() - lastQueuedAt < 8000) {
    lastOfflineSaveWasDuplicate = true; return true;
  }
  ScopedSemaphoreLock lock(offlineQueueMutex, pdMS_TO_TICKS(2000));
  if (!lock) return false;
  // Reserve capacity for final outcomes/recovery; stop accepting new money intents first.
  size_t freeBytes = LittleFS.totalBytes() - LittleFS.usedBytes();
  if ((!replay && (countPendingTransactionsUnlocked() >= 256 || freeBytes < 131072)) || freeBytes < 8192) return false;
  if (!LittleFS.exists("/fare-pending") && !LittleFS.mkdir("/fare-pending")) return false;
  JsonDocument document;
  document["schema"] = 2; document["deviceId"] = tx.deviceId;
  document["offlineTransactionId"] = tx.offlineTransactionId; document["status"] = tx.status;
  document["paymentMethod"] = tx.paymentMethod; document["credential"] = tx.credential;
  document["fareAmount"] = tx.fareAmount; document["vehicleId"] = tx.vehicleId;
  document["latitude"] = tx.latitude; document["longitude"] = tx.longitude; document["timestamp"] = tx.timestamp;
  document["attempts"] = tx.attempts; document["nextAttemptAt"] = tx.nextAttemptAt;
  String canonical; serializeJson(document, canonical);
  document["crc"] = String(premier_payment::crc32(canonical.c_str(), canonical.length()), HEX);
  String row; serializeJson(document, row);
  if (document.overflowed() || row.length() > 2048) return false;
  String path = "/fare-pending/" + tx.offlineTransactionId + ".json";
  File file = LittleFS.open(path + ".tmp", "w"); if (!file) return false;
  bool written = file.print(row) == row.length(); file.flush(); file.close(); journalWrites++;
  File verify = LittleFS.open(path + ".tmp", "r");
  bool verified = verify && verify.readString() == row; if (verify) verify.close();
  if (!written || !verified) return false;
  if (LittleFS.exists(path)) {
    if (LittleFS.exists(path + ".bak") && !LittleFS.remove(path + ".bak")) return false;
    if (!LittleFS.rename(path, path + ".bak")) return false;
  }
  if (!LittleFS.rename(path + ".tmp", path)) {
    if (LittleFS.exists(path + ".bak")) LittleFS.rename(path + ".bak", path);
    return false;
  }
  LittleFS.remove(path + ".bak");
  if (!replay) { lastQueuedCredential = tx.credential; lastQueuedAt = millis(); }
  pendingOfflineCount = countPendingTransactionsUnlocked(); return true;
}''')
function('bool removeOfflineTransaction(', r'''
bool removeOfflineTransaction(const String &identity) {
  ScopedSemaphoreLock lock(offlineQueueMutex, pdMS_TO_TICKS(2000));
  if (!lock) return false;
  String path = identity.length() ? "/fare-pending/" + identity + ".json" : currentQueuePath;
  if (!path.startsWith("/fare-pending/") || (identity.length() && !safeIntentId(identity))) return false;
  // Caller must have persisted a final result or renamed the raw record into quarantine.
  if (LittleFS.exists(path) && !LittleFS.remove(path)) return false;
  pendingOfflineCount = countPendingTransactionsUnlocked(); return true;
}''')
function('bool readQueueHead(', r'''
bool readQueueHead(String &row, bool &oversized) {
  ScopedSemaphoreLock lock(offlineQueueMutex, pdMS_TO_TICKS(2000)); if (!lock) return false;
  File directory = LittleFS.open("/fare-pending"); if (!directory) return false;
  uint32_t now = (uint32_t)time(nullptr);
  for (File file = directory.openNextFile(); file; file = directory.openNextFile()) {
    String path = String(file.path());
    if (!path.endsWith(".json")) { file.close(); continue; }
    oversized = file.size() > 2048; row = oversized ? "" : file.readString(); file.close();
    JsonDocument entry;
    if (!oversized && !deserializeJson(entry, row) && (entry["nextAttemptAt"] | 0u) > now && now > 1700000000u) continue;
    currentQueuePath = path; directory.close(); return true;
  }
  directory.close(); return false;
}''')
replace('  if (!document.containsKey("schema")) return true; // Legacy rows are migrated on their next retry.',
        '  if (!document.containsKey("schema")) return false; // Unverifiable legacy evidence is quarantined.')
replace('    && identity == String(document["offlineTransactionId"] | "");',
        '    && identity == String(document["offlineTransactionId"] | "")\n    && (String(document["state"] | "") == "CONFIRMED" || String(document["state"] | "") == "PERMANENT_FAILURE" || String(document["state"] | "") == "RECONCILIATION_REQUIRED");')
replace('  document["vehicleId"] = tx.vehicleId; document["capturedAt"] = tx.timestamp;',
        '  document["vehicleId"] = tx.vehicleId; document["deviceId"] = tx.deviceId; document["capturedAt"] = tx.timestamp;\n  document["fareAmount"] = tx.fareAmount;')
replace('if (state == "REVIEW") document["credential"] = tx.credential;',
        'if (state == "RECONCILIATION_REQUIRED") document["credential"] = tx.credential;')
function('bool quarantineQueueHead(', r'''
bool quarantineQueueHead(const String &reason) {
  ScopedSemaphoreLock lock(offlineQueueMutex, pdMS_TO_TICKS(2000)); if (!lock) return false;
  if (!currentQueuePath.startsWith("/fare-pending/")) return false;
  if (!LittleFS.exists("/fare-quarantine") && !LittleFS.mkdir("/fare-quarantine")) return false;
  String path = "/fare-quarantine/" + generateOfflineTransactionId();
  File note = LittleFS.open(path + ".reason", "w"); if (!note) return false;
  bool noted = note.print(reason) == reason.length(); note.flush(); note.close();
  return noted && LittleFS.rename(currentQueuePath, path + ".raw");
}''')
replace('    tx.offlineTransactionId = document["offlineTransactionId"] | "";',
        '    tx.deviceId = document["deviceId"] | "";\n    tx.offlineTransactionId = document["offlineTransactionId"] | "";')
replace('if (!safeIntentId(tx.offlineTransactionId) || tx.credential.length() == 0',
        'if (tx.deviceId != terminalId || tx.timestamp.startsWith("UNSYNCED") || !safeIntentId(tx.offlineTransactionId) || tx.credential.length() == 0')
replace('      if (!saveOfflineTransaction(tx, true) || !removeOfflineTransaction(tx.offlineTransactionId)) break;\n      continue;', '      continue;')
replace('        tx.nextAttemptAt = now + premier_payment::retryDelay(tx.attempts);',
        '        tx.status = "UNKNOWN";\n        tx.nextAttemptAt = now + premier_payment::retryDelay(tx.attempts) + (esp_random() % 11);')
replace('        // Append before removing: power loss may duplicate a queue row but cannot lose an intent.\n        if (!saveOfflineTransaction(tx, true) || !removeOfflineTransaction(tx.offlineTransactionId)) break;',
        '        // Atomically replace only this record; never rewrite unrelated pending intents.\n        if (!saveOfflineTransaction(tx, true)) break;')
replace('result.success ? "SUCCEEDED" : outcome == premier_payment::Outcome::Rejected ? "REJECTED" : "REVIEW"',
        'result.success ? "CONFIRMED" : outcome == premier_payment::Outcome::Rejected ? "PERMANENT_FAILURE" : "RECONCILIATION_REQUIRED"')
# Terminal cannot safely fabricate a capture timestamp before clock synchronization.
replace('  if (!saveOfflineTransaction(tx, false)) { showStorageFailure(); return; }',
        '  if (tx.timestamp.startsWith("UNSYNCED")) { showStorageFailure(); return; }\n  if (!saveOfflineTransaction(tx, false)) { showStorageFailure(); return; }')
replace('  pendingOfflineCount = getPendingTransactionCount();\n  Serial.println("[LITTLEFS] Mounted.', r'''
  if (!LittleFS.exists("/fare-pending")) LittleFS.mkdir("/fare-pending");
  if (!LittleFS.exists("/fare-quarantine")) LittleFS.mkdir("/fare-quarantine");
  // Preserve legacy queues: no stored original device identity means unattended replay is unsafe.
  File legacy = LittleFS.open(OFFLINE_QUEUE_FILE, "r"); size_t legacySize = legacy ? legacy.size() : 0; if(legacy) legacy.close();
  if (legacySize && !LittleFS.rename(OFFLINE_QUEUE_FILE, "/fare-quarantine/legacy-" + generateOfflineTransactionId() + ".raw")) return false;
  File pendingDir = LittleFS.open("/fare-pending");
  for (File item = pendingDir.openNextFile(); item; item = pendingDir.openNextFile()) {
    String path = String(item.path()); item.close();
    if (path.endsWith(".bak")) {
      String original = path.substring(0, path.length() - 4);
      if (!LittleFS.exists(original) && !LittleFS.rename(path, original)) return false;
    }
  }
  pendingDir.close();
  pendingOfflineCount = getPendingTransactionCount();
  Serial.println("[LITTLEFS] Mounted.''')
# Exact result reference already parsed and validated by ArduinoJson, not the legacy string extractor.
replace('  result.code = parsed ? String(decoded["code"] | "") : "PAYMENT_UNKNOWN";',
        '  result.code = parsed ? String(decoded["code"] | "") : "PAYMENT_UNKNOWN";\n  result.transactionId = reference;')
p.write_text(s, encoding='utf-8', newline='\n')
print('Refined staging firmware: per-intent atomic files, reserved capacity, stable device identity, quarantine, bounded retry.')
