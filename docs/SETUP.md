# Setup Guide — Action ou Vérité Live

This guide takes you from a fresh clone to a debug build running against a real
Firebase backend with AI-generated challenges. It is honest about what needs
**real credentials** (Firebase project, Google OAuth web client id, Anthropic
API key, optionally a TURN server) — those cannot be checked into the repo and
must be provisioned by you.

Estimated time for a first-time setup: 45–90 minutes, most of it in the Firebase
console.

---

## 1. Prerequisites

| Tool | Version | Notes |
| ---- | ------- | ----- |
| **Android Studio** | Ladybug (2024.2) or newer | Bundled Gradle/AGP work with this project. |
| **JDK** | 17 | The build targets `JavaVersion.VERSION_17` (see `app/build.gradle.kts`). Use Android Studio's embedded JBR 17 or a standalone JDK 17. |
| **Android SDK** | API 35 (`compileSdk`/`targetSdk = 35`), min API 24 | Install via the SDK Manager. |
| **Node.js** | 20 (LTS) | Cloud Functions pin `"node": "20"` in `functions/package.json` and `firebase.json` (`runtime: nodejs20`). Other majors will fail `firebase deploy`. |
| **Firebase CLI** | latest | `npm install -g firebase-tools`, then `firebase login`. |
| **A physical Android device** | Android 7.0+ (API 24) | **Required** for audio/video — emulators cannot exercise real camera/mic/WebRTC. See §9. |

Verify your toolchain:

```bash
java -version          # expect 17.x
node -v                # expect v20.x
firebase --version
adb devices            # your real device should be listed (USB debugging on)
```

---

## 2. Clone and project layout

```bash
git clone <your-fork-url> action-verite-live
cd action-verite-live
```

Relevant files you will touch or create:

```
secrets.properties.example     -> copy to secrets.properties      (git-ignored)
app/google-services.json.example -> replace with real app/google-services.json (git-ignored)
firebase.json                  -> Firebase deploy config (already present)
.firebaserc                    -> default project alias = "action-verite-live"
firestore.rules / storage.rules / firestore.indexes.json
functions/                     -> Cloud Functions (TypeScript, Node 20)
```

The default Firebase project alias in `.firebaserc` is `action-verite-live`.
Either reuse that exact project id when creating your Firebase project, or run
`firebase use --add` to point the CLI at your own project id.

---

## 3. Create the Firebase project

> **Real credentials required.** None of this can be stubbed; the app talks to a
> live Firebase backend.

1. Go to the [Firebase console](https://console.firebase.google.com/) and
   **Add project**. If you want zero extra config, name the project so its
   **project id** is `action-verite-live` (matches `.firebaserc`). Otherwise pick
   any id and update the alias later (§6).
2. Enable **Google Analytics** when prompted (Crashlytics and Analytics both use
   it; the app depends on `firebase-analytics` + `firebase-crashlytics`).

### 3.1 Register the Android app

1. In **Project settings → Your apps → Add app → Android**.
2. **Package name:** `com.actionverite.live`
   - The debug build appends a suffix (`applicationIdSuffix = ".debug"`), so the
     *installed* debug app id is `com.actionverite.live.debug`. Register both
     package names (`com.actionverite.live` **and** `com.actionverite.live.debug`)
     if you want Google sign-in to work in debug — Google OAuth matches the
     SHA-1 + package name pair. Add the release package for production.
3. **Debug signing certificate SHA-1 (and SHA-256):** required for Google
   sign-in. Get them with:

   ```bash
   ./gradlew :app:signingReport
   ```

   Copy the SHA-1 (and SHA-256) for the `debug` variant into the Firebase app's
   settings (**Add fingerprint**). Repeat with your release keystore before
   shipping.
4. **Download `google-services.json`** and place it at:

   ```
   app/google-services.json
   ```

   This file is git-ignored (see `.gitignore`). The committed
   `app/google-services.json.example` is only a placeholder — the build needs the
   **real** file or `google-services` plugin processing will produce an app that
   cannot reach Firebase.

---

## 4. Enable Firebase services

Enable each of the following in the Firebase console. The app and functions all
assume they exist.

### 4.1 Authentication

**Build → Authentication → Get started**, then enable these sign-in providers:

| Provider | Console steps / what you need |
| -------- | ----------------------------- |
| **Email/Password** | Toggle on. (Optionally enable email-link.) |
| **Phone** | Toggle on. Real SMS needs billing (Blaze) and may need reCAPTCHA / Play Integrity. For dev, add test phone numbers under Phone → *Phone numbers for testing*. |
| **Google** | Toggle on. This auto-creates OAuth clients in Google Cloud. You will copy the **Web client id** from here in §5. |
| **Apple** | Toggle on. Requires an Apple Developer account, a Services ID, key, and configured return URL. **Real Apple credentials required**; cannot be faked. |
| **Facebook** | Toggle on. Requires a Facebook app (App ID + App Secret) from the Meta developer console, plus the OAuth redirect URI Firebase shows you. **Real Facebook app required.** |

> Apple and Facebook are optional to *build* the app, but the corresponding
> sign-in buttons will fail at runtime until their real provider credentials are
> configured. Email/Password, Phone, and Google are enough for end-to-end testing.

### 4.2 Cloud Firestore

**Build → Firestore Database → Create database**. Start in your preferred region.
Security rules are deployed from this repo (`firestore.rules`) in §7 — do not
hand-edit them in the console.

### 4.3 Cloud Storage

**Build → Storage → Get started**. Rules come from `storage.rules` (deployed in
§7). Used for avatars/media.

### 4.4 Cloud Functions

**Build → Functions**. Cloud Functions (Gen 2) require the **Blaze
(pay-as-you-go)** plan. Upgrade the project under the gear/Usage & billing.
The three callables (`generateChallenge`, `moderateText`, `requestMatch`) are
deployed from `functions/` in §8.

### 4.5 Cloud Messaging (FCM)

Enabled automatically with the project; no console toggle needed. The app
depends on `firebase-messaging` for push notifications. Make sure the Android app
is registered (§3.1) so an FCM sender id is present in `google-services.json`.

### 4.6 Crashlytics

**Release & Monitor → Crashlytics → Enable**. The `firebase.crashlytics` Gradle
plugin and `firebase-crashlytics` dependency are already wired. The first crash
report appears after a debug session has run.

### 4.7 App Check

**Build → App Check**. This is **mandatory**, not optional: all three Cloud
Functions set `enforceAppCheck: true` (see `functions/src/index.ts`), so calls
without a valid App Check token are rejected.

1. Register the Android app for App Check with the **Play Integrity** provider
   (recommended for production).
2. For local development and CI, register a **debug token**: run the debug app
   once, find the printed debug token in Logcat, and add it under App Check →
   your app → **Manage debug tokens**. Without this, AI challenge generation,
   moderation, and matchmaking will fail with App Check errors during dev.

---

## 5. Configure `secrets.properties` (Google web client id)

> **Real credential required.**

The app reads `GOOGLE_WEB_CLIENT_ID` at build time (exposed as a `BuildConfig`
field in `app/build.gradle.kts`) for Google sign-in via Credential Manager.

1. Copy the example:

   ```bash
   cp secrets.properties.example secrets.properties
   ```

2. Find the **Web client id** (the OAuth 2.0 client of type *Web application*
   that Firebase created when you enabled Google sign-in):
   - Firebase console → **Authentication → Sign-in method → Google → Web SDK
     configuration**, or
   - Google Cloud console → **APIs & Services → Credentials → OAuth 2.0 Client
     IDs → "Web client (auto created by Google Service)"**.
3. Put it in `secrets.properties`:

   ```properties
   GOOGLE_WEB_CLIENT_ID=1234567890-abcdefg.apps.googleusercontent.com
   ```

`secrets.properties` is git-ignored. Alternatively, export it as an environment
variable of the same name (the build falls back to `System.getenv`):

```bash
export GOOGLE_WEB_CLIENT_ID=1234567890-abcdefg.apps.googleusercontent.com
```

> Note: this is the **Web** client id, not the Android client id. Using the
> Android id here will make Google sign-in fail.

---

## 6. Point the Firebase CLI at your project

```bash
firebase login
firebase use action-verite-live        # if you reused the default id
# or, for your own project id:
firebase use --add                     # pick your project, alias it "default"
```

Confirm:

```bash
firebase projects:list
firebase use
```

---

## 7. Deploy Firestore & Storage rules and indexes

These are server-side gatekeepers; deploy them before (or together with)
functions so reads/writes are protected.

```bash
firebase deploy --only firestore:rules,firestore:indexes,storage
```

Source files: `firestore.rules`, `firestore.indexes.json`, `storage.rules`
(all referenced from `firebase.json`).

---

## 8. Configure and deploy Cloud Functions

> **Real credential required: Anthropic API key.**

The functions generate challenges and moderate text via the Anthropic Claude API
(`@anthropic-ai/sdk`). The key is read from `process.env.ANTHROPIC_API_KEY`,
wired through **Firebase Secret Manager** via `defineSecret("ANTHROPIC_API_KEY")`
in `functions/src/index.ts` — it is **never** committed to the repo.

### 8.1 Install dependencies

```bash
cd functions
npm install
```

### 8.2 Set the Anthropic secret

Get an API key from the [Anthropic console](https://console.anthropic.com/),
then store it in Secret Manager:

```bash
firebase functions:secrets:set ANTHROPIC_API_KEY
# paste the key when prompted (sk-ant-...)
```

- `generateChallenge` throws `failed-precondition` if the secret is unset.
- `moderateText` degrades gracefully to a deterministic rule-based classifier
  when no key is configured, so moderation never silently disappears — but for
  real AI moderation you still want the key set.
- The default model is `claude-haiku-4-5` (`MODEL` constant in
  `functions/src/challenge.ts`); you can swap it to `claude-opus-4-8` for higher
  quality at higher cost/latency.

### 8.3 Build, lint, and deploy

```bash
# from functions/
npm run lint          # eslint (also runs as a predeploy hook)
npm run build         # tsc -> lib/

# deploy just the functions:
npm run deploy        # == firebase deploy --only functions
```

`firebase.json` runs `npm run lint` and `npm run build` as **predeploy** hooks,
so a lint/type error blocks the deploy. After the first successful deploy, the
three callables (`generateChallenge`, `moderateText`, `requestMatch`) appear
under **Build → Functions**.

### 8.4 Deploy everything at once (optional)

From the repo root, this deploys rules, indexes, storage, **and** functions in
one shot:

```bash
firebase deploy
```

### 8.5 Local emulators (optional, no real Anthropic calls billed if key unset)

```bash
# from functions/
npm run serve         # build + functions emulator
# or, from repo root, the full suite (auth/functions/firestore/storage + UI):
firebase emulators:start
```

Emulator ports (from `firebase.json`): auth 9099, functions 5001, firestore
8080, storage 9199, plus the Emulator UI.

---

## 9. WebRTC: STUN/TURN configuration and device testing

The live video uses `stream-webrtc-android`. ICE servers are configured in
`data/.../webrtc/WebRtcRepositoryImpl.kt`. Out of the box it ships only a public
**STUN** server:

```kotlin
PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
```

### STUN vs TURN — what you need

- **STUN** (already wired) lets peers discover their public address and works for
  most home/Wi-Fi networks where a direct peer-to-peer path exists.
- **TURN** is a relay for the substantial fraction of users behind symmetric
  NATs, mobile carrier-grade NAT, or restrictive firewalls. **Without a TURN
  server, calls will silently fail to connect for those users.** A production
  deployment of a 2–6 player live video game **must** add TURN.

### Adding TURN

1. Stand up or buy a TURN service (e.g. self-hosted **coturn**, or a managed
   provider such as Twilio NTS, Xirsys, Cloudflare Calls TURN, Metered, etc.).
2. **Do not hardcode long-lived TURN credentials in the app.** The code comment
   in `WebRtcRepositoryImpl.kt` notes: *"TURN credentials are fetched from the
   backend in production."* The intended pattern is to mint short-lived,
   time-limited TURN credentials server-side (e.g. an additional callable
   function) and add them to the ICE server list at runtime, e.g.:

   ```kotlin
   PeerConnection.IceServer.builder("turn:turn.example.com:3478?transport=udp")
       .setUsername(ephemeralUsername)   // from backend
       .setPassword(ephemeralCredential) // from backend
       .createIceServer()
   ```

3. Until you provision TURN, expect connectivity to work on permissive networks
   only. This is a known limitation, not a bug.

### A/V must be tested on a real device

> Audio and video **cannot** be meaningfully tested on the Android emulator.
> Camera, microphone, and real network/NAT traversal all require physical
> hardware.

- Use **two or more real devices** (different networks ideally) to validate
  matchmaking + live call end to end.
- Grant **camera** and **microphone** permissions when prompted (handled via
  `accompanist-permissions`).
- For testing across networks/NAT types, having TURN configured (above) makes the
  difference between "works on my Wi-Fi" and "actually connects."

---

## 10. Build and run the app

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Install and run on a connected real device:

```bash
./gradlew :app:installDebug
# then launch "Action ou Vérité Live" (debug) from the launcher, or:
adb shell am start -n com.actionverite.live.debug/com.actionverite.live.MainActivity
```

Or just press **Run** in Android Studio with the device selected.

If the build fails on `google-services`, you have the placeholder
`google-services.json` — replace it with the real one (§3.1). If Google sign-in
fails, recheck `GOOGLE_WEB_CLIENT_ID` (§5) and the SHA-1 fingerprints (§3.1). If
challenge generation / moderation / matchmaking fail, recheck App Check (§4.7)
and the Anthropic secret + deployed functions (§8).

---

## 11. Run the tests

Pure-Kotlin domain unit tests (fast, no device, no Firebase):

```bash
./gradlew :domain:test
```

Full project compile + unit tests:

```bash
./gradlew test
```

Instrumented/UI tests run on a device/emulator (note: A/V behavior still needs a
real device):

```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## 12. What absolutely needs real credentials (honest summary)

| Thing | Needed for | Can it be stubbed? |
| ----- | ---------- | ------------------ |
| `app/google-services.json` (real) | The app to reach Firebase at all | **No** — placeholder won't connect. |
| `GOOGLE_WEB_CLIENT_ID` | Google sign-in | No, for Google sign-in specifically. |
| SHA-1/SHA-256 fingerprints in Firebase | Google sign-in | No. |
| `ANTHROPIC_API_KEY` (Secret Manager) | AI challenge generation; real AI moderation | `generateChallenge` requires it; `moderateText` falls back to rules. |
| App Check (debug token or Play Integrity) | Calling any Cloud Function | **No** — App Check is enforced. |
| Blaze billing plan | Cloud Functions, real SMS phone auth | No. |
| Apple / Facebook provider config | Apple/Facebook sign-in buttons | Those buttons only; rest of app works without them. |
| TURN server + ephemeral credentials | Reliable video connectivity across NATs | Works on permissive networks without it; required for production. |
| A physical Android device | Audio/video / WebRTC testing | **No** — emulator cannot do real A/V. |

---

## 13. Quick reference — all commands

```bash
# Toolchain check
java -version; node -v; firebase --version; adb devices

# Secrets
cp secrets.properties.example secrets.properties   # then edit GOOGLE_WEB_CLIENT_ID
# place real app/google-services.json from Firebase console

# Firebase CLI
firebase login
firebase use action-verite-live        # or: firebase use --add

# Fingerprints for Google sign-in
./gradlew :app:signingReport

# Functions
cd functions && npm install
firebase functions:secrets:set ANTHROPIC_API_KEY
npm run build && npm run lint
npm run deploy                         # functions only

# Rules + indexes + storage (from repo root)
firebase deploy --only firestore:rules,firestore:indexes,storage
firebase deploy                        # everything (rules + functions)

# App
./gradlew :app:assembleDebug
./gradlew :app:installDebug

# Tests
./gradlew :domain:test                 # domain unit tests
./gradlew test                         # all unit tests
```
