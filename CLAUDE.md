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
LockActivity  no-UI panic lock behind the launcher shortcut "Khóa ngay" (res/xml/shortcuts.xml)
core/         pure Kotlin, no Android, JVM-tested
  crypto/     Aead (AES-256-GCM, random 12-byte nonce), Argon2id (Bouncy Castle) + KdfParams limits, Bytes
  format/     Base32 (RFC 4648), ByteWriter / ByteReader (bounds-checked big-endian)
  vault/      VaultHeader (key slots), VaultCrypto (key hierarchy), BackupFormat (.lvbackup v1 / v2), RecoveryKey,
              Attachments (chunked encrypted files), UnlockThrottle (wrong-password delays)
  totp/       Totp (RFC 6238, otpauth:// parsing), OtpMigration (Google Authenticator otpauth-migration:// QR,
              hand-written protobuf reader)
  card/       PaymentCard (brand by BIN, Luhn except Napas, format / mask, MM/YY expiry)
  backup/     BackupReminder (when to nag: never backed up, 30 days, > 10 changes; 7-day snooze)
  generator/  PasswordGenerator (SecureRandom, BIP39 passphrases), StrengthEstimator (zxcvbn-like: common /
              leet passwords, keyboard runs, sequences, repeats, years, dates → bits + Vietnamese warnings)
  health/     PasswordHealth (weak, reused, older than 1 year, missing 2FA / URL, score)
  importer/   Csv (RFC 4180) + CsvImporter (Bitwarden, Chrome, Firefox, KeePass(XC), generic by header names;
              strict UTF-8 / UTF-16 BOM decode, unknown columns → custom fields, duplicates skipped)
  autofill/   DomainMatcher (registrable domain, punycode, multi-part suffixes, no https→http), FormClassifier
              (username / password fields from hints, HTML, inputType, Vietnamese labels), AutofillMatcher +
              TrustedBrowsers
autofill/     MatKhauAutofillService, AutofillActivity (+ AutofillUi: unlock, pick, link, save), StructureParser
              (AssistStructure → FormField), AppIdentity (label + signing-cert SHA-256, needs QUERY_ALL_PACKAGES)
  qr/         QrDecoder (ZXing on a luminance frame)
data/         VaultDatabase (SQLiteOpenHelper: meta + items), VaultRepository (session, CRUD, backup; Dispatchers.IO)
model/        Entry (serialized to JSON then encrypted as a whole; favorite, passwordUpdatedAt, passwordHistory
              ≤ 20 via withPassword; 0.5.0: types CARD / IDENTITY with CardInfo / IdentityInfo, attachments
              = AttachmentRef list), BackupPayload, Categories. New fields need defaults (old items / backups)
security/     AutoLockManager, BiometricKeyManager (Keystore key) + BiometricPrompter, SecureClipboard,
              VaultClipboard (Compose LocalClipboard → SecureClipboard), UnlockLimiter, AutoBackup (SAF tree),
              PanicLockTileService (Quick Settings tile "Khóa Mật Khẩu"),
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
- **Storage:** `vault.db`, `PRAGMA secure_delete=ON`, rollback journal (WAL disabled so secure_delete covers old
  pages), a `DatabaseErrorHandler` that renames a corrupt file to `vault.db.corrupt-<ms>` instead of Android's
  default delete (Welcome then warns), entry JSON ≤ 1 MiB (rows must fit the ~2 MB CursorWindow; oversized rows are
  counted as corrupted, never read), exactly two tables, no plaintext column:
  `meta(k TEXT PK, v BLOB)` (`vault_header`, `biometric_wrapped_vek`) and `items(id TEXT PK, fmt INT, nonce BLOB,
  ct BLOB)`. Every DB call is a suspend function on `Dispatchers.IO`; create and restore replace the vault in one
  transaction (`VaultDatabase.replaceVault`). `fmt` 1 = an entry; `fmt` 2 = one chunk of an attachment.
- **Attachments (0.5.0):** no extra table. A file is cut into 512 KiB chunks (CursorWindow is ~2 MB), each sealed
  with the VEK, AAD = `"localvault/attachment/v1" + vaultId + u32 index + u32 count + attachmentId`, stored as
  `items` row `att:<attachmentId>:<index>`, fmt 2. Name, MIME, size, chunk count and SHA-256 live in the encrypted
  `Entry.attachments`; opening checks every chunk + size + SHA-256. Limits: 10 MB per file, 40 MB in total, 20 per
  entry. Add / delete attachment and delete entry write the entry and its chunks in one transaction; unlock skips
  fmt-2 rows (`entryItems`) and deletes orphan chunks only when every entry decrypted. Viewing (images, text) is
  in-app; anything else is exported unencrypted after a warning.
- **Binary formats** (big-endian, "bytes" = u32 length + data). Any change must bump the version and keep reading
  the old one.
  - Header: `"LVHD" | u16 1 | bytes vaultId(16) | u8 kdf=1 | u32 memKiB | u32 iter | u8 par | slotMK | u8 hasRecovery
    | [slotRecovery]`; slot = `bytes salt(16) | bytes nonce(12) | bytes ct(48)`; slot AAD =
    `"localvault/key-slot/v1" + type (1 password, 2 recovery) + vaultId`.
  - Backup `.lvbackup` v2 (0.5.0): `"LVBK" | u16 2 | header (both slots) | bytes nonce | bytes ct | u32 n |
    n × (bytes rowId | bytes nonce | bytes ct)`; AAD of the entry block = `"localvault/backup/v1" + every byte before
    the nonce`; plaintext = JSON `BackupPayload`. The trailing rows are the attachment chunks copied verbatim from
    the DB (already sealed with the VEK and bound to vault / file / position); restore requires exactly the rows the
    entries reference, each decrypting and matching its SHA-256, else it is treated as tampering. Written streaming
    (`BackupFormat.write`), max 96 MB; attachments that fail verification at export are left out and reported.
    v1 (`u16 1`, no row section) is still read. Opens with the master password **or** the Recovery Key valid when it
    was exported, never depends on the Keystore. Restoring keeps the backup's VEK / vaultId, so chunks stay valid.
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
  (old backups still open with the old key). The key screen has no copy button (clipboard history / cloud sync,
  the key must live off the device); it uses the bundled JetBrains Mono (`res/font`, OFL in `licenses/`) with digits
  tinted (`recoveryKeyText`) and a note that the alphabet is A–Z + 2–7 only. "In phiếu khẩn cấp" draws an A4 page on a
  Canvas (`ui/RecoveryKeyPrinter`, PrintManager, no WebView, no app-side temp file; auto-lock suspended while the
  system print UI is open) after warning against cloud printers / "Save as PDF". Leaving the screen requires typing
  2 random groups back from paper (`RecoveryKey.groupMatches`, same normalization as `parse`).
- **Manifest:** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE removed with `tools:node="remove"`;
  `allowBackup=false`; data extraction rules exclude everything; `configChanges` so secrets never go into
  `savedInstanceState`. CAMERA is requested only when the scan button is pressed.
- **Auto-lock:** "on leaving the app", 1, 5, 15 or 30 min (default 1, no "never"); inactivity timer in the
  foreground; screen off ⇒ lock now; system file picker / permission dialog suspends locking for at most 5 min;
  deadline re-checked with `elapsedRealtime` when coming back. Lock = VEK zeroed, entries dropped, screen stack
  reset, clipboard cleared if we copied something, except when locking because the app went to the background
  (the user is probably pasting elsewhere; the clipboard timer still clears it).
- **Clipboard:** cleared after 10/30/60/120 s (default 30), marked IS_SENSITIVE; not cleared when we can see it now
  holds something else the user copied. Text-field "Copy" / "Cut" (selection toolbar, Ctrl+C) also goes through it:
  `AppRoot` and `AutofillFrame` provide `VaultClipboard` as Compose `LocalClipboard` (paste is untouched).
- **Wrong password throttle (0.5.0):** master password and Recovery Key share one counter (`UnlockLimiter`,
  `AppSettings.unlockFailures`, written with `commit()`): 4 free tries, then 30 s, 1, 5, 15, 30 min. Measured with
  `elapsedRealtime` + `Settings.Global.BOOT_COUNT`: a reboot restarts the full wait instead of clearing it. Biometric
  unlock is not counted. Never wipes the vault.
- **Backups (0.5.0):** `AppSettings.lastBackupAt` + `changesSinceBackup` (bumped by `VaultRepository.onVaultChanged`)
  drive the reminder banner. Optional auto-backup: a SAF tree with a persisted permission; ~20 s after the last change
  (or 5 s after an unlock with pending changes) while unlocked, writes `matkhau-auto-<stamp>.lvbackup` (the same
  encrypted format), keeps the 5 newest of its own files. Errors show in the banner and Settings.
- **Inactivity:** `Activity.onUserInteraction` misses soft-keyboard text and dialog touches, so text fields
  (`TrackedTextField`, `SecretField`) and dialog content (`Modifier.trackUserActivity`) report activity through
  `LocalUserActivity`. Messages to the user go through `vm.snackbar = "…"` (a queue, not state).
- **QR scan:** CameraX `ImageAnalysis` → Y plane → `QrDecoder` → buffer zeroed; preview in COMPATIBLE mode
  (TextureView) so FLAG_SECURE covers it; camera unbound when the scanner leaves composition. Nothing saved or logged.
- **Security screen** checks everything at runtime (PackageManager permissions, window flags, KeyInfo, keyguard,
  camera permission), never hard-coded ticks.

- **Autofill:** every dataset is an *authentication* dataset pointing at `AutofillActivity`, so no password reaches
  the system until the user picks one. The service parses the system-provided `AssistStructure` and freezes the
  target + field ids into each PendingIntent (`ParsedScreen.writeTo`; fill-in extras cannot override them; a random
  data URI per intent so a stale PendingIntent is never reused); the activity never re-reads the structure the client
  sends and rejects a `callingActivity` from another package. A browser package never becomes an App target.
  Both activities hide other apps' overlay windows (tapjacking).
  - Web: only from `TrustedBrowsers` package names **and** after the user confirmed that browser, pinning its
    signing cert (`AppSettings.trustedBrowserCert`); otherwise `UnverifiedBrowser` (matches nothing, shows a trust
    card with installer + cert, warns loudly when the cert changed; trust is revocable in Settings, cert rotation
    history accepted). "From a store" = installer in a known-store list AND that installer is a system app. Domain match is strict (`DomainMatcher`):
    same host or a subdomain of the saved host, `www.` ignored, never https→http; no guessed registrable domain.
    A non-matching web entry is blocked; an entry without URL may be given the current domain after a warning.
    Save / assign let the user pick the exact host or a parent (`DomainMatcher.saveCandidates`, never a shared suffix).
  - In-app web pages (a non-browser app reporting `webDomain`: Zalo / Facebook in-app browsers, WebViews) keep the
    domain as `AutofillTarget.App.webHost`: it only *narrows* (a linked entry matches only if its URL also matches),
    never links, never gets cards, and the save flow does not link. Each field takes the domain of its own frame; the
    form uses the focused field's frame and drops fields from other frames; address-bar nodes are skipped. Every
    PendingIntent extra is always written (null when unused) because the client can add missing extras.
  - App: only entries the user linked to that package **and** signing cert (`Entry.linkedApps`; cert rotation history
    accepted). Name-based suggestions only for apps installed from a known store; sideloaded apps get a warning.
  - Forms: current vs new password fields are told apart; fill goes to current, save prefers the new value.
  - Cards (0.5.0): `FormClassifier.classifyCard` (Android hints, `cc-*` autocomplete, Vietnamese / English labels)
    takes the card fields out of login classification. Cards are not bound to a site: offered only on a confirmed
    browser page that is not `http`, or an app with a readable cert (`AutofillMatcher.cardAllowed`). A card never
    takes the fill fast path: `CardPick` always confirms the receiver (host / app, non-store warning, "HTTPS not
    reported" warning when the scheme is null), then `SecretGuard` is forced (biometric) even with the option off;
    no device auth at all = no card fill. Word-only card detection needs a CVV or expiry field too, so login forms
    with "số thẻ thư viện" stay logins. Never saved from forms.
  - Attachments are owned by the repository: `save()` / `importEntries()` ignore the caller's `attachments` and keep
    the stored list (stale snapshots from autofill / edit screens cannot drop or resurrect files). Large writes use
    `commitTx` (transaction outside the session monitor, generation checked at the end, RAM state updated only after
    COMMIT) so `lock()` never waits; CSV / 2FA imports use it too.
  - Auto-backup pruning always keeps the file it just wrote and the newest *complete* one
    (`AppSettings.autoBackupLastCompleteDoc`), so degraded exports (corrupted entries, skipped attachments) never
    push the last full backup out; a restore during the run (`VaultRepository.restoreCount`) means the file is not
    counted. An export aborts (`VaultLockedException`, partial file deleted) when the vault locks mid-write, so no
    VEK copy outlives the lock. Unlock-time orphan cleanup only runs if `writeMutex` is free (`tryLock`). Wrong
    password attempts are counted *before* Argon2id (`UnlockLimiter.tryBegin`); a non-password error restores the
    exact previous counter state (`cancel`).
  - Save: credentials stay in RAM (`MatKhauApp.pendingAutofillSave`, 10 min expiry, nonce-bound to its intent,
    cleared on lock (the save screen closes) or when that save screen closes), never in an Intent; username is
    editable (two-step logins).
  - `VaultViewModel.hostIsAutofill` skips the forced flows there; the main app runs them via `ensureForcedFlows()`
    on every unlock and every resume.
- **Accessibility trojans:** Android 14+ marks the windows `setAccessibilityDataSensitive(YES)`
  (`AccessibilityGuard.protectWindow`, re-applied in `onContentChanged`) and every composable showing secrets uses
  `Modifier.sensitive()` (Compose `isSensitiveData`), so only `isAccessibilityTool` services (TalkBack…) can read or
  act. `isAccessibilityTool` is self-declared (only Play checks it), so a sideloaded trojan can claim it: every
  enabled service that is not a system app, or not (from a known store AND declared tool), counts as risky; the
  Security + Unlock screens list them with package names, and while any exists `SecretGuard` is enforced even if the
  option is off. Dialogs are separate windows: their content calls `SensitiveWindow()`. Also warns on a non-system
  keyboard. (The 0.4.0 gap, selection-toolbar "Copy" bypassing SecureClipboard, is closed by `VaultClipboard`.)
  Optional `AppSettings.requireAuthForSecrets`: `SecretGuard` asks for biometric (device credential only when no
  biometric is enrolled) before reveal, copy of a secret, opening Edit, un-hiding a field, or an autofill fill (30 s
  grace, reset on lock, action dropped if the vault locked during the prompt; switching it off always prompts). Components get
  the gate through `LocalSecretGuard`.
- **CSV import:** preview, then one transaction (`VaultDatabase.insertItems`); the plaintext preview is dropped on
  lock / back; afterwards the user is pushed to delete the CSV file.

## Known limits

Strings (decrypted entries, typed passwords) cannot be wiped from RAM, only the VEK is; clipboard clearing is best
effort (process killed = timer lost, keyboards may keep a history); no protection against root / Accessibility
malware; the strength meter is rough; the clipboard "is it still ours" check relies on the clip label, which a
keyboard / OEM clipboard manager could change.

## Next

Done from Phase 2 / 3: password history, favourites, password health, panic lock (tile + shortcut), CSV import,
Autofill with phishing protection, anti accessibility-trojan layers, backup reminder + auto-backup, Google
Authenticator import, cards / identities (+ card autofill), encrypted attachments, wrong-password throttle. Not planned: decoy vault, hardware FIDO2 keys (no platform API), passkeys /
multiple vaults unless asked; inline (keyboard) autofill suggestions could come later.
