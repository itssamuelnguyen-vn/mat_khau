# Mật Khẩu

Offline password manager for the owner's Android phone (sideloaded APK, never on the Play Store). No server, no
account, no INTERNET permission: everything is encrypted on the device. Ported from the "LocalVault 0.1.0" prototype
(`D:\dev\LocalVault-0.1.0\LocalVault`); the decisions recorded there win over the original feature spec. **UI is
Vietnamese only** (strings inline). Code comments are Vietnamese too (kept from the port), names are English; docs
for developers (this file) are English, README is Vietnamese. The owner is a Rails developer new to Android: explain
Android concepts by comparison with Rails. Build rules (no Android Studio, no emulator) are in `~/.claude/CLAUDE.md`.

## Build, test, install

```powershell
$env:JAVA_HOME='D:\dev\android-sdk\jbr'
.\gradlew.bat testDebugUnitTest                                   # JVM tests: crypto, formats, TOTP, QR, generator
powershell -ExecutionPolicy Bypass -File tool\build_apk.ps1        # = Ctrl+Shift+B → build\apk\matkhau-<ver>-<stamp>.apk
```

From WSL redirect Gradle's output to a file and feed stdin from /dev/null, otherwise the call can hang:
`powershell.exe -NoProfile -Command "cd D:\dev\customized_apps\mat_khau; .\gradlew.bat assembleRelease *> build\gradle.log" < /dev/null`
(the log is UTF-16: `iconv -f utf-16le -t utf-8 build/gradle.log`).

Release is R8-shrunk (every `android.util.Log` call stripped) and signed with the local debug key. **Changing the
signing key means uninstalling, which deletes the vault**: export a backup first. Bump `versionCode` /
`versionName` in `app/build.gradle.kts` for each APK given to the phone.

Stack: Kotlin, Compose + Material 3 (+ icons extended), same AGP/Kotlin/Compose versions as the other native apps
(`gradle/libs.versions.toml`). Plain `SQLiteOpenHelper` (**no Room / KSP**, no navigation-compose), biometric,
CameraX + ZXing core (QR scan, **no ML Kit**), Bouncy Castle (**only** Argon2id), kotlinx.serialization.
compile/target SDK 37, minSdk 28 (Android 9). `MainActivity` is a `FragmentActivity` (BiometricPrompt needs it).

## Layout

```
MatKhauApp    wiring: repository (+ load() off the main thread), settings, autoLock, biometric; screen off → lock
MainActivity  FLAG_SECURE (+ no recents screenshot on 13+), user interaction → auto-lock timer
core/         pure Kotlin, no Android, JVM-tested
  crypto/     Aead (AES-256-GCM, random 12-byte nonce), Argon2id (Bouncy Castle) + KdfParams limits, Bytes
  format/     Base32 (RFC 4648), ByteWriter / ByteReader (bounds-checked big-endian)
  vault/      VaultHeader (key slots), VaultCrypto (key hierarchy), BackupFormat (.lvbackup), RecoveryKey
  totp/       Totp (RFC 6238, otpauth:// parsing)
  generator/  PasswordGenerator (SecureRandom, BIP39 passphrases), StrengthEstimator (rough)
  qr/         QrDecoder (ZXing on a luminance frame)
data/         VaultDatabase (SQLiteOpenHelper: meta + items), VaultRepository (session, CRUD, backup; Dispatchers.IO)
model/        Entry (serialized to JSON then encrypted as a whole), BackupPayload, Categories
security/     AutoLockManager, BiometricKeyManager (Keystore key) + BiometricPrompter, SecureClipboard,
              SecurityAudit (real runtime checks), AppSettings (non-sensitive prefs only)
ui/           VaultViewModel (screen stack + all actions), AppRoot (router), screens/, components/, theme/
```

## Security model (do not weaken)

- **Key hierarchy:** VEK = 32 random bytes generated once. It is *wrapped* (AES-GCM) by KEK₁ = Argon2id(master
  password, salt₁), by KEK₂ = Argon2id(Recovery Key, salt₂), and optionally by a Keystore AES key that needs
  BIOMETRIC_STRONG on every use (alias `localvault.biometric.v1`, invalidated by new enrolment, StrongBox if any).
  No password hash is stored: wrong password = GCM fails to unwrap. Changing the password only re-wraps the VEK.
  Creating a vault runs Argon2id twice (password + Recovery Key). The master password is Unicode-NFC-normalized
  before UTF-8 (`MasterPassword.toBytes`), so Vietnamese diacritics typed as NFC or NFD give the same key; unlock
  also tries the raw bytes when they differ (`unlockCandidates`, vaults / backups made by 0.1.0).
- **Argon2id:** default 64 MiB, 3 passes, 1 lane, 32-byte output, 16-byte salt. Header values outside 8–256 MiB,
  1–20 passes, 1–8 lanes are rejected (256 MiB cap: a crafted backup must not OOM the phone).
- **Items:** the whole `Entry` (title, URL, tags, dates included) is one AES-GCM blob. AAD =
  `"localvault/item/v1" + vaultId + itemId` (no swapping ciphertexts between rows). Search runs in RAM after unlock
  (title, username, URL, category label, tags; never password / TOTP / notes). Items that fail to decrypt are
  counted and reported; a key proven by a key slot (password / Recovery Key) always opens the vault
  (`UnlockPolicy`), only an unverified biometric key with *every* item failing is treated as a wrong key.
- **Storage:** `vault.db`, `PRAGMA secure_delete=ON`, exactly two tables, no plaintext column:
  `meta(k TEXT PK, v BLOB)` (`vault_header`, `biometric_wrapped_vek`) and `items(id TEXT PK, fmt INT, nonce BLOB,
  ct BLOB)`. Every DB call is a suspend function on `Dispatchers.IO`; create and restore replace the vault in one
  transaction (`VaultDatabase.replaceVault`).
- **Binary formats** (big-endian, "bytes" = u32 length + data). Any change must bump the version and keep reading
  the old one.
  - Header: `"LVHD" | u16 1 | bytes vaultId(16) | u8 kdf=1 | u32 memKiB | u32 iter | u8 par | slotMK | u8 hasRecovery
    | [slotRecovery]`; slot = `bytes salt(16) | bytes nonce(12) | bytes ct(48)`; slot AAD =
    `"localvault/key-slot/v1" + type (1 password, 2 recovery) + vaultId`.
  - Backup `.lvbackup`: `"LVBK" | u16 1 | header (both slots) | bytes nonce | bytes ct`; AAD =
    `"localvault/backup/v1" + every byte before the nonce`; plaintext = JSON `BackupPayload`; max 64 MB. Opens with
    the master password **or** the Recovery Key valid when it was exported, never depends on the Keystore.
  - Recovery Key: 20 random bytes → Base32 without padding → 8 groups of 4. Parsing drops non-alphanumerics,
    upper-cases, maps 0→O, 1→I, 8→B.
  - Biometric blob: `u8 2 | bytes iv | bytes ct` (v1 from 0.1.0 had no AAD and is rejected: re-enable), GCM AAD = `"localvault/biometric/v1" + vaultId` (a blob from
    another vault, e.g. after a restore, fails).
- **Restore:** validate format → password or Recovery Key *of the backup* → GCM check → confirm overwrite → one
  transaction → biometric reset → lock. With a vault on the device, restore is only offered while unlocked.
- **Session guard:** `VaultRepository.lock()` bumps a generation counter (even when already locked). Unlock, create,
  change password, new Recovery Key, save / delete capture it first and only commit (DB write + state) if no lock
  happened meanwhile, else `VaultLockedException` and nothing is written. The UI clears its state on every lock via
  `lockCount`.
- **Recovery Key unlock** forces a new master password (`AppSettings.mustChangePassword`, survives lock / biometric
  unlock until done). A Recovery Key screen left without "đã chép" (`recoveryKeyPending`) makes the next unlock
  regenerate and show a new key; that screen keeps the display on and stretches the inactivity timer to 10 min.
  Both flags are cleared by a restore. Regenerating the key invalidates the old one for the vault
  (old backups still open with the old key). The key screen has no copy button.
- **Manifest:** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE removed with `tools:node="remove"`;
  `allowBackup=false`; data extraction rules exclude everything; `configChanges` so secrets never go into
  `savedInstanceState`. CAMERA is requested only when the scan button is pressed.
- **Auto-lock:** "on leaving the app", 1, 5, 15 or 30 min (default 1, no "never"); inactivity timer in the
  foreground; screen off ⇒ lock now; system file picker / permission dialog suspends locking for at most 5 min;
  deadline re-checked with `elapsedRealtime` when coming back. Lock = VEK zeroed, entries dropped, screen stack
  reset, clipboard cleared if we copied something, except when locking because the app went to the background
  (the user is probably pasting elsewhere; the clipboard timer still clears it).
- **Clipboard:** cleared after 10/30/60/120 s (default 30), marked IS_SENSITIVE; not cleared when we can see it now
  holds something else the user copied.
- **Inactivity:** `Activity.onUserInteraction` misses soft-keyboard text and dialog touches, so text fields
  (`TrackedTextField`, `SecretField`) and dialog content (`Modifier.trackUserActivity`) report activity through
  `LocalUserActivity`. Messages to the user go through `vm.snackbar = "…"` (a queue, not state).
- **QR scan:** CameraX `ImageAnalysis` → Y plane → `QrDecoder` → buffer zeroed; preview in COMPATIBLE mode
  (TextureView) so FLAG_SECURE covers it; camera unbound when the scanner leaves composition. Nothing saved or logged.
- **Security screen** checks everything at runtime (PackageManager permissions, window flags, KeyInfo, keyguard,
  camera permission), never hard-coded ticks.

## Known limits

Strings (decrypted entries, typed passwords) cannot be wiped from RAM, only the VEK is; clipboard clearing is best
effort (process killed = timer lost, keyboards may keep a history); no protection against root / Accessibility
malware; the strength meter is rough; the clipboard "is it still ours" check relies on the clip label, which a
keyboard / OEM clipboard manager could change.

## Next (Phase 2 of the spec)

Autofill, password history, favourites, weak / reused password detection, health dashboard.
