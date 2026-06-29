# Action ou Vérité Live

A production-grade native **Android** app for playing **Truth or Dare** ("Action ou
Vérité") with **2–6 players over live WebRTC video**. Each turn, the active player
chooses **Truth** or **Dare**; for a Dare the other players vote a difficulty from
**1 to 5**, the average is computed, and an **AI generates a challenge that matches
that difficulty exactly** — personalized to the player's age, language, country and
interests, and gated by per-user content limits and moderation.

The app is built with **Clean Architecture + MVVM** across a multi-module Gradle
project, a **Firebase** backend (Auth, Firestore, Cloud Functions, Storage,
Messaging, Crashlytics, App Check) and **Cloud Functions** that call the
**Anthropic Claude** API for challenge generation and text moderation.

> **Project status (read this first):** the **domain core is fully implemented and
> unit-tested** (~80 passing JVM tests). The **Android UI/data layers and the
> Cloud Functions backend are implemented but not yet build-verified end-to-end**:
> assembling the APK and running the app require a configured Firebase project, a
> `google-services.json`, a Google OAuth web client id, an Anthropic API key and a
> device/emulator. See [Project status](#project-status) for the honest breakdown.

---

## Table of contents

- [Concept &amp; gameplay](#concept--gameplay)
- [Features](#features)
- [Architecture](#architecture)
- [Directory layout](#directory-layout)
- [Tech stack](#tech-stack)
- [The difficulty vote → average → AI flow](#the-difficulty-vote--average--ai-flow)
- [Testing](#testing)
- [Build &amp; run](#build--run)
- [Backend (Cloud Functions &amp; Firebase)](#backend-cloud-functions--firebase)
- [Project status](#project-status)
- [Further docs](#further-docs)

---

## Concept &amp; gameplay

Each game is a live video room of **2 to 6 players** (`Room.MIN_PLAYERS = 2`,
`Room.MAX_PLAYERS = 6`). Play proceeds in turns driven by a deterministic state
machine (`GameStateMachine`):

1. **Choose type** — the active player picks **Truth** (`TRUTH`) or **Dare**
   (`DARE`).
2. **Collect votes** (Dare only) — every *other* player votes a difficulty `1..5`.
   The active player cannot vote on their own dare; the last vote per voter wins.
3. **Generate** — the votes are aggregated to an average, and the AI produces one
   unique challenge matching that difficulty, the player's profile and limits.
4. **Perform** — the player performs the dare / answers the truth on video.
5. **Rate &amp; advance** — the outcome (`COMPLETED` / `FAILED`) is recorded, XP and
   badges are applied, and the turn rotates to the next present player. Rounds end
   when `maxRounds` is reached or fewer than `MIN_PLAYERS` remain present.

The full turn lifecycle (`LOBBY → CHOOSING_TYPE → COLLECTING_VOTES → GENERATING →
PERFORMING → RATING → … → FINISHED`) lives in one auditable, exhaustively-tested
reducer in the framework-free domain layer.

## Features

- **Live video for 2–6 players** — peer-to-peer WebRTC video grid with an adaptive
  layout (`ComputeVideoGridLayoutUseCase`) that arranges 2–6 tiles sensibly.
- **Truth or Dare turns** — deterministic `GameStateMachine` covering type choice,
  voting, generation, performance, rating, turn rotation and end-of-game.
- **Difficulty voting (1–5) → average → AI** — `AggregateDifficultyUseCase` computes
  the mean and a clamped target that the AI matches exactly (see
  [below](#the-difficulty-vote--average--ai-flow)).
- **AI-generated, personalized challenges** — `RequestChallengeUseCase` builds a
  full personalization context (age, language, country, interests, limits, recent
  history for anti-repeat) and calls a Cloud Function backed by **Anthropic Claude**,
  which returns structured JSON via a forced tool call.
- **Moderation &amp; safety** — layered defense-in-depth: an on-device
  `ContentModerator` + `FilterChallengeUseCase` (age / `ContentLimits` / category
  gate) plus a server-side Claude classifier with a deterministic rule-based
  fallback. Minors and non-opted-in players can never receive adult content.
- **Matchmaking** — a weighted compatibility scorer (`MatchmakingScorer`:
  language · interests · level · age · country · ping) and group formation
  (`FormMatchUseCase`), mirrored server-side by the `requestMatch` Cloud Function
  for server-authoritative queueing.
- **Social** — friends &amp; requests, presence, in-room chat (`FriendRepository`,
  `ChatRepository`, `Friend`, `FriendRequest`, `ChatMessage`).
- **Progression** — closed-form XP↔level mapping (`LevelSystem`), XP awards
  (`AwardXpUseCase`, `ApplyTurnRewardsUseCase`), badges (`EvaluateBadgesUseCase`,
  `BadgeId`) and leaderboards.
- **Auth** — Firebase Auth with email and Google sign-in via Credential Manager.
- **Profile, settings, onboarding, home, leaderboard** — full Compose feature set
  (see [directory layout](#directory-layout)).

## Architecture

The project follows **Clean Architecture** with **MVVM** in the presentation layer,
split into three Gradle modules with a strict one-way dependency direction:

```
        ┌───────────────────────────────────────────────┐
        │                     :app                        │  Jetpack Compose UI (MVVM)
        │   features/*, ui/*, navigation, Hilt wiring     │  → depends on :domain, :data
        └────────────────┬───────────────┬────────────────┘
                         │               │
                         ▼               ▼
           ┌──────────────────┐   ┌──────────────────────────┐
           │     :domain      │◄──│           :data           │  Firebase + WebRTC impls,
           │  pure Kotlin/JVM │   │  Android lib, Hilt DI      │  DTOs/mappers, Functions svc
           │  models, repos,  │   │  implements :domain repos  │  → depends on :domain
           │  use cases (the  │   └──────────────────────────┘
           │  innermost layer)│
           └──────────────────┘
```

- **`:domain`** — **pure Kotlin/JVM, zero Android dependencies.** Holds the business
  rules: models, repository *interfaces*, and use cases. Because it depends on
  nothing outward, it builds fast and is **fully unit-testable on the JVM** (it is
  the innermost, most stable Clean Architecture layer). Its only dependencies are
  `kotlinx-coroutines-core` and `javax.inject`.
- **`:data`** — Android library that **implements the domain repository interfaces**
  using Firebase (Auth, Firestore, Functions, Storage, Messaging, Crashlytics,
  Analytics, App Check) and **WebRTC** (`stream-webrtc-android`). Contains DTOs +
  mappers, the `FunctionsService` callable wrapper, the WebRTC signaling client, and
  **Hilt** DI modules binding implementations to domain interfaces.
- **`:app`** — **Jetpack Compose** UI (Material 3) using **MVVM**: each feature has a
  `*UiState`, a `@HiltViewModel`, a public `*Route` composable wired into navigation,
  and a stateless `*Screen` for previews/testability. Navigation is
  `navigation-compose`; images via Coil; permissions via accompanist.

### MVVM convention

Every feature mirrors the same shape (see `feature/profile/` and `feature/auth/` as
the canonical templates):

```kotlin
data class FeatureUiState( /* sensible defaults */ )

@HiltViewModel
class FeatureViewModel @Inject constructor(/* domain use cases / repositories */)
    : ViewModel() {
    val uiState: StateFlow<FeatureUiState> = /* stateIn(...) or MutableStateFlow */
    // one-shot nav events via Channel(...).receiveAsFlow(), or callback lambdas
}

@Composable
fun FeatureRoute(/* nav callbacks */, viewModel: FeatureViewModel = hiltViewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    FeatureScreen(ui = ui, /* lambdas */)   // stateless, previewable
}
```

## Directory layout

```
ActionVeriteLive/
├── settings.gradle.kts            # includes :app, :domain, :data
├── build.gradle.kts               # plugins declared `apply false`
├── gradle/libs.versions.toml      # version catalog (single source of versions)
├── secrets.properties.example     # GOOGLE_WEB_CLIENT_ID template (git-ignored real file)
├── firebase.json / .firebaserc    # Firebase project + emulator config
├── firestore.rules / .indexes     # Firestore security rules & indexes
├── storage.rules                  # Cloud Storage security rules
│
├── domain/                        # :domain — pure Kotlin/JVM, unit-tested
│   └── src/
│       ├── main/kotlin/com/actionverite/live/domain/
│       │   ├── common/            # DomainResult, DomainError, DispatcherProvider
│       │   ├── model/             # Challenge, Game, Room, Player, UserProfile,
│       │   │                      #   Matchmaking, Moderation, Progression,
│       │   │                      #   Social, Leaderboard, VideoGrid
│       │   ├── repository/        # repository INTERFACES (Auth, Game, Room, Rtc,
│       │   │                      #   Challenge, Matchmaking, Moderation, Social, …)
│       │   └── usecase/
│       │       ├── game/          # GameStateMachine, AggregateDifficulty,
│       │       │                  #   RequestChallenge, TurnRotation, VideoGrid
│       │       ├── matchmaking/   # MatchmakingScorer, FindMatch, FormMatch
│       │       ├── moderation/    # ContentModerator, FilterChallenge
│       │       └── progression/   # LevelSystem, AwardXp, ApplyTurnRewards, Badges
│       └── test/kotlin/…          # ~80 JVM unit tests (JUnit4 + Truth + coroutines-test)
│
├── data/                          # :data — Android library, Firebase + WebRTC
│   └── src/main/kotlin/com/actionverite/live/data/
│       ├── common/                # AuthFlows, FirestoreFlows, Firebase ext, dispatchers
│       ├── di/                    # Hilt modules: Firebase, Repository, Domain bindings
│       ├── remote/                # FirestorePaths, FunctionsService, dto/ (Dtos, Mappers)
│       ├── repository/            # *RepositoryImpl for each domain interface
│       └── webrtc/                # SignalingClient, WebRtcRepositoryImpl
│
├── app/                           # :app — Jetpack Compose UI (MVVM)
│   └── src/main/
│       ├── kotlin/com/actionverite/live/
│       │   ├── ActionVeriteApp.kt, MainActivity.kt, di/AppModule.kt
│       │   ├── feature/           # auth, onboarding, home, matchmaking, game,
│       │   │                      #   friends, leaderboard, profile, settings
│       │   ├── ui/                # components/, navigation/ (AvNavHost, Destinations),
│       │   │                      #   theme/ (Color, Type, Shape, Theme)
│       │   └── notification/      # AvFirebaseMessagingService
│       └── res/                   # strings, themes, launcher icons, backup rules
│
└── functions/                     # Cloud Functions backend (TypeScript, Node 20)
    └── src/
        ├── index.ts               # callables: generateChallenge, moderateText, requestMatch
        ├── challenge.ts           # AI Truth/Dare generation (Anthropic Claude)
        ├── moderation.ts          # text moderation (Claude classifier + rule fallback)
        ├── matchmaking.ts         # server-authoritative queue + group formation
        └── types.ts               # shared types mirroring the domain models
```

## Tech stack

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.0 (`2.0.21`), JDK 17 toolchain |
| Build | Gradle multi-module + version catalog (`libs.versions.toml`), AGP `8.7.3`, KSP |
| UI | Jetpack Compose + Material 3, navigation-compose, Coil, accompanist-permissions |
| Architecture | Clean Architecture + MVVM, Hilt (DI), Coroutines / Flow |
| Backend SDKs | Firebase BOM `33.7.0`: Auth, Firestore, Functions, Storage, Messaging, Crashlytics, Analytics, App Check |
| Auth | Firebase Auth + Google sign-in via Credential Manager (`androidx.credentials`, `googleid`) |
| Realtime media | `io.getstream:stream-webrtc-android` |
| Cloud Functions | TypeScript on Node 20, `firebase-functions` v2, `firebase-admin` |
| AI | Anthropic Claude via the official `@anthropic-ai/sdk` (structured tool output) |
| Testing (domain) | JUnit4, Truth, `kotlinx-coroutines-test`; (app) adds MockK, Turbine, Compose UI test |

Minimum SDK 24, target/compile SDK 35, `applicationId = com.actionverite.live`.

## The difficulty vote → average → AI flow

This is the heart of the game. It is implemented in the **domain** so it is pure and
testable, then executed against the AI via a Cloud Function:

1. **Voting.** During a Dare, each *other* player casts a `DifficultyVote(voterUid,
   value)` with `value ∈ 1..5`. The `GameStateMachine` rejects a vote from the active
   player and replaces any earlier vote by the same voter.
2. **Aggregation.** `CloseVoting` calls `AggregateDifficultyUseCase`, which:
   - drops the active player's own vote (defensive),
   - keeps the **last** vote per voter (last-write-wins),
   - computes the exact **average**, and the rounded, clamped **target** (1..5),
   - returns `DifficultyResult(average, target, voteCount)` — falling back to
     `DifficultyResult.NEUTRAL` (3) when there are no eligible votes.
3. **Personalized request.** `RequestChallengeUseCase` assembles a `ChallengeRequest`
   carrying the player's age (derived from birth date + today), language, country,
   interests, `ContentLimits`, the `DifficultyResult` (only for `DARE`), and the
   player's **recent challenge signatures** for anti-repeat.
4. **AI generation.** The data layer calls the `generateChallenge` Cloud Function,
   which prompts **Anthropic Claude** to *"match the difficulty EXACTLY to `target`
   on a 1..5 scale (group voted an average of `average`)"* and to respect the
   player's `maxDifficulty`. The model is forced to return structured JSON through a
   single `challenge` tool, so the function always gets well-formed output.
5. **Safety gate &amp; retry.** Every generated challenge passes through
   `FilterChallengeUseCase` (age + `ContentLimits` + on-device `ContentModerator`).
   If it is rejected, its signature is remembered and the use case retries
   generation up to `DEFAULT_MAX_ATTEMPTS` (3) so the player still gets a valid
   challenge. The server independently forces `isAdult = false` for minors /
   non-opted-in players as defense in depth.

The same weighted formula and 1..5 bounds are shared between the domain and the
backend, and `AggregateDifficultyUseCase` is covered by dedicated unit tests
(average computation, last-write-wins, self-vote exclusion, empty-vote fallback).

## Testing

The **domain layer ships with ~80 passing JVM unit tests** (JUnit4 + Truth +
`kotlinx-coroutines-test`). They cover the difficulty aggregation, the game state
machine, turn rotation, the video-grid layout, challenge requesting, matchmaking
scoring/formation, moderation/filtering, the level system, XP/badge rewards and
model invariants.

Run them with:

```bash
./gradlew :domain:test
```

Because `:domain` is pure Kotlin/JVM, this task needs **no Android SDK, no Firebase
config and no device** — only the JDK 17 toolchain (auto-provisioned by the
foojay resolver) and the ability to resolve dependencies from Maven/Google.

> Note: a `:domain:test` run still configures the root Gradle build, which references
> the Android Gradle Plugin and other plugins; the **first** run therefore needs
> network access to download the Gradle plugins and dependencies. In a fully offline
> environment without those artifacts cached, plugin resolution will fail before the
> tests execute — this is an environment/network constraint, not a defect in the
> test sources.

The `:app` module additionally declares MockK, Turbine and Compose UI test
dependencies for ViewModel and UI tests; the backend uses ESLint + `tsc` for type
checking (`npm --prefix functions run lint && npm --prefix functions run build`).

## Build &amp; run

### Prerequisites

- JDK 17 (the Gradle toolchain will auto-provision a matching JDK).
- Android SDK with platform 35; an emulator or device on Android 7.0+ (API 24).
- Network access to resolve Gradle plugins and dependencies (Google Maven, Maven
  Central, JitPack).
- A **Firebase project** plus the secrets below.

### Required configuration (not committed — see `.gitignore`)

1. **`google-services.json`** — download from your Firebase project and place it at
   `app/google-services.json`.
2. **`secrets.properties`** — copy `secrets.properties.example` to
   `secrets.properties` and set `GOOGLE_WEB_CLIENT_ID` (the OAuth 2.0 **Web** client
   id used by Credential Manager Google sign-in). It can also be supplied as an env
   var of the same name.
3. **`ANTHROPIC_API_KEY`** — set as a Cloud Functions secret (see
   [Backend](#backend-cloud-functions--firebase)); not needed to build the app.

### Commands

```bash
# Domain unit tests (no Android/Firebase needed)
./gradlew :domain:test

# Build the debug APK (needs Android SDK + google-services.json)
./gradlew :app:assembleDebug

# Install on a connected device/emulator
./gradlew :app:installDebug

# Build everything
./gradlew build
```

## Backend (Cloud Functions &amp; Firebase)

The `functions/` directory is a TypeScript (Node 20) Firebase Functions project
exposing three **authenticated, App-Check-enforced** callables consumed by the
Android `FunctionsService`:

- **`generateChallenge`** — AI Truth/Dare generation via Anthropic Claude
  (structured tool output), matching difficulty, personalization, limits and
  anti-repeat signatures.
- **`moderateText`** — text safety classification (Claude classifier with a
  deterministic rule-based fallback; always blocks the most severe categories).
- **`requestMatch`** — server-authoritative matchmaking over the `matchQueue`
  collection, using the same weighted scoring as the on-device `MatchmakingScorer`,
  forming a `rooms` document the client can deserialize.

Firestore/Storage **security rules** and Firestore **indexes** live at the repo root
(`firestore.rules`, `firestore.indexes.json`, `storage.rules`) and are wired up in
`firebase.json`. The Anthropic API key is stored in **Secret Manager** and never
committed.

```bash
# Install deps
npm --prefix functions install

# Lint + type-check + build
npm --prefix functions run lint
npm --prefix functions run build

# Provide the Anthropic key (stored in Secret Manager, never in code)
firebase functions:secrets:set ANTHROPIC_API_KEY

# Run locally against the emulator suite
npm --prefix functions run serve

# Deploy
firebase deploy --only functions,firestore:rules,firestore:indexes,storage
```

## Project status

This repository is **partially verified**. Be honest about what is and isn't proven:

| Area | Status |
| --- | --- |
| `:domain` (models, use cases, business rules) | **Implemented and unit-tested** (~80 JVM tests). The pure-Kotlin core — game state machine, difficulty aggregation, matchmaking, moderation, progression — is the most complete and trustworthy part of the codebase. |
| `:data` (Firebase + WebRTC impls, Hilt DI) | **Implemented**, not build-verified end-to-end. Compiling/running requires the Android SDK and a real Firebase project (`google-services.json`). |
| `:app` (Compose UI, MVVM, navigation) | **Implemented** following the documented MVVM pattern. Building the APK and exercising the UI require the Android SDK, Firebase config, the Google web client id and a device/emulator. |
| `functions/` (Cloud Functions backend) | **Implemented** (challenge generation, moderation, matchmaking). Running it requires a Firebase project and an `ANTHROPIC_API_KEY` secret; it has not been deployed/exercised from this repo. |
| Security rules &amp; indexes | **Authored** (`firestore.rules`, `firestore.indexes.json`, `storage.rules`); deploy and test against your own project before relying on them. |

In short: **the domain core builds and tests cleanly on the JVM**, while the Android
UI/data layers and the backend are written but **require a configured Firebase
project, API keys and device testing to fully build, run and validate.** Treat them
as a strong, well-structured starting point rather than a shipped, battle-tested
binary.

## Further docs

- `secrets.properties.example` — the secrets template and what each value is for.
- `functions/` — backend source with extensive file-level documentation of the AI,
  moderation and matchmaking flows.
- In-code KDoc — the domain use cases and models carry detailed KDoc that maps each
  rule back to the original spec; start with `usecase/game/` and `model/Challenge.kt`.
- A `docs/` directory and a dedicated `SETUP.md` are **not present yet**; the
  build/run and backend sections above are the authoritative setup instructions for
  now.

---

*"Action ou Vérité Live" — Truth or Dare, live, with friends.*
