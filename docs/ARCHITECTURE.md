# Architecture — Action ou Vérité Live

This document describes the architecture of **Action ou Vérité Live**, a native
Android Truth-or-Dare game played over live WebRTC video by 2–6 players, with
AI-generated challenges, a Firebase backend, smart matchmaking, social features
and a progression/XP system.

The goal of the architecture is to keep the **rules of the game** (turn
lifecycle, difficulty aggregation, XP/levels, badges, matchmaking scoring,
moderation gating) in a place that is pure, framework-free and exhaustively
unit-testable, while isolating everything volatile — Firebase, WebRTC, Android,
Compose — behind well-defined boundaries.

---

## 1. Clean Architecture in three Gradle modules

The app is a multi-module Gradle project (`settings.gradle.kts` →
`rootProject.name = "ActionVeriteLive"`, `include(":app", ":domain", ":data")`).
Each module maps to one layer of Clean Architecture:

| Module    | Gradle plugin            | Responsibility                                                                 | May depend on            |
| --------- | ------------------------ | ------------------------------------------------------------------------------ | ------------------------ |
| `:domain` | `kotlin.jvm` (pure JVM)  | Entities, repository **interfaces**, use cases. The rules of the game.         | nothing (stdlib + coroutines-core only) |
| `:data`   | Android library + Hilt   | Repository **implementations**: Firebase, WebRTC, Cloud Functions; Hilt DI.    | `:domain`                |
| `:app`    | Android app + Compose + Hilt | Jetpack Compose UI (Material 3), MVVM ViewModels, navigation, notifications. | `:domain`, `:data`       |

### The dependency rule

Dependencies point **inward**, toward the domain. `:app` and `:data` both depend
on `:domain`; **`:domain` depends on nothing Android and nothing Firebase**. This
is enforced mechanically by the build:

- `domain/build.gradle.kts` applies `kotlin.jvm` (not the Android plugin) and
  pulls in only `kotlinx-coroutines-core`. It physically *cannot* import
  `android.*`, `com.google.firebase.*`, `androidx.compose.*` or `org.webrtc.*`.
- `data/build.gradle.kts` declares `implementation(project(":domain"))` and
  nothing pointing back up to `:app`.
- `app/build.gradle.kts` declares `implementation(project(":domain"))` and
  `implementation(project(":data"))`.

Because the dependency rule is wired into the module graph, a compile error is
the result of any accidental layering violation (e.g. a use case importing
Firebase). The domain is therefore the **stable core**: UI frameworks, the
backend and the transport (WebRTC) can all change without touching it.

```
                         ┌──────────────────────────────────────────────┐
                         │                  :app                         │
                         │   Jetpack Compose UI · Material 3 · MVVM       │
                         │   ViewModels · navigation-compose · Hilt       │
                         │   feature.<name>.*  ·  ui.*                     │
                         └───────────────┬───────────────┬──────────────┘
                                         │               │
                         depends on      │               │  depends on
                                         ▼               ▼
        ┌────────────────────────────────────┐   ┌──────────────────────────────┐
        │               :data                │   │            :domain            │
        │  Firebase (Auth/Firestore/Funcs/   │   │  PURE KOTLIN — no Android,    │
        │  Storage/Messaging) · WebRTC mesh  │──▶│  no Firebase, no Compose      │
        │  Cloud Functions client · Hilt @Binds │  │                              │
        │  RepositoryImpls implement domain  │   │  model/      (entities)       │
        │  interfaces                        │   │  repository/ (interfaces)     │
        └────────────────────────────────────┘   │  usecase/    (game rules)     │
                         │                        │  common/     (Result/Error,   │
                         │  depends on            │              Dispatchers)     │
                         └───────────────────────▶└──────────────────────────────┘

   Direction of dependencies: ALWAYS inward (toward :domain).
   :domain knows nothing about the layers that depend on it.
```

---

## 2. The domain layer (`:domain`)

Package root: `com.actionverite.live.domain`.

### 2.1 `common/` — result and concurrency primitives

- **`DomainResult<T>`** (`common/DomainResult.kt`) — a sealed `Success(data)` /
  `Failure(error)` type used everywhere instead of throwing for *expected*
  failures. Helpers: `map`, `onSuccess`, `onFailure`, `getOrNull`,
  `errorOrNull`, `asSuccess`, `asFailure`. This forces every call site to handle
  both branches.
- **`DomainError`** — an exhaustive, typed catalogue of failures the UI can
  render: `Network`, `Timeout`, `Unauthenticated`, `PermissionDenied`,
  `NotFound(what)`, `Validation(reason)`, `RoomFull(capacity)`, `Underage`,
  `Moderation(reason)`, `Unknown(cause)`. Each carries a default user-facing
  `message`.
- **`DispatcherProvider`** (`common/DispatcherProvider.kt`) — abstraction over
  `main`/`io`/`default` `CoroutineDispatcher`s so use cases never reference
  `Dispatchers` directly. `DefaultDispatcherProvider` is the production impl;
  tests inject a single test dispatcher. (`:data` binds an
  `AndroidDispatcherProvider` to this interface.)

### 2.2 `model/` — entities

Plain immutable Kotlin data classes / enums with their invariants baked in:
`Game.kt` (`GameSession`, `Turn`, `GamePhase`, `RoundOutcome`), `Room.kt`
(`Room` with `MIN_PLAYERS = 2`, `MAX_PLAYERS = 6`), `Challenge.kt`,
`Player.kt`, `UserProfile.kt`, `Progression.kt` (XP/levels/badges),
`Matchmaking.kt`, `Moderation.kt`, `Leaderboard.kt`, `Social.kt`,
`VideoGrid.kt`. The capacity bounds (`Room.MIN_PLAYERS..MAX_PLAYERS`) are
referenced by the game state machine and the grid-layout use case so every layer
agrees on the same limits.

### 2.3 `repository/` — boundaries (interfaces only)

The domain declares *what* it needs, not *how* it is fulfilled. Each interface
exposes realtime data as `kotlinx.coroutines.flow.Flow` and one-shot operations
as `suspend` functions returning `DomainResult`:

- `AuthRepository` — `authState: Flow<String?>`, `signIn`, `signInAnonymously`,
  phone verification, link/unlink, `signOut`, `deleteAccount`.
- `UserRepository` — `observeCurrentProfile()`, `getProfile`,
  `createOrUpdateProfile`, `updateInterests`, `updateLimits`, `updateStats`.
- `RoomRepository` — create/join/leave rooms, invite codes, media state, ping.
- `GameRepository` — `observeSession(roomId)` plus transition commands
  (`startGame`, `chooseType`, `castDifficultyVote`, `submitOutcome`,
  `commitSession`, `endGame`).
- `ChallengeRepository` — `generate(request)`, `recentSignatures`,
  `recordServed` (AI generation boundary).
- `MatchmakingRepository` — `enqueue(...)` streaming `MatchmakingState`
  (`Idle`/`Searching`/`Found`/`Failed`), `candidatePool`, `cancel`.
- `ModerationRepository` — server-side `screenText`, `submitReport`.
- `RtcRepository` (`RtcRepository.kt`) — the WebRTC session boundary:
  `connect`/`disconnect`, `observeTracks(): Flow<List<MediaTrackRef>>`,
  `observeStats(): Flow<List<RtcStats>>`, mic/camera toggles, `switchCamera`.
  `MediaTrackRef` and `RtcStats` are domain-side, framework-opaque handles.
- `SocialRepositories.kt` — `FriendRepository`, `ChatRepository`,
  `LeaderboardRepository`.
- `NotificationRepository`.

### 2.4 `usecase/` — the rules of the game

Use cases are small, single-purpose, constructor-injected (`@Inject`) classes
that compose repositories and other use cases. The **pure** ones take no
repositories and are deterministic, which is what makes the rule-set
exhaustively unit-testable (see `domain/src/test/...`).

**Game (`usecase/game/`)**

- **`GameStateMachine`** — the single source of truth for turn transitions. A
  pure reducer `reduce(session, event): GameSession` over a sealed `GameEvent`
  hierarchy (`StartGame`, `ChooseType`, `CastVote`, `CloseVoting`,
  `ChallengeReady`, `SubmitOutcome`, `NextTurn`, `EndGame`). It encodes the full
  lifecycle `LOBBY → CHOOSING_TYPE → (DARE only) COLLECTING_VOTES → GENERATING →
  PERFORMING → RATING → next turn / FINISHED`. Invalid transitions throw
  `IllegalStateException` (treated as protocol/programmer errors, not user
  failures). It delegates vote tallying to `AggregateDifficultyUseCase` and
  cursor movement to `TurnRotation`.
- **`AggregateDifficultyUseCase`** — averages the 1–5 difficulty votes cast by
  the *other* players for the active player's dare ("la moyenne est calculée").
  Last-vote-per-voter wins, the active player's own vote is ignored, and an empty
  ballot yields `DifficultyResult.NEUTRAL` (3).
- **`TurnRotation`** (object) — pure turn-order arithmetic; finds the next
  *present* player after the current index, skipping disconnected players, and
  reports `wrapped` so the state machine can bump the round counter.
- **`ComputeVideoGridLayoutUseCase`** — computes the optimized 2–6 tile grid
  (`rows × columns`) for portrait/landscape ("disposition automatique
  optimisée"), validated against `Room.MIN_PLAYERS..MAX_PLAYERS`.
- **`RequestChallengeUseCase`** — orchestrates AI challenge generation: builds a
  personalized `ChallengeRequest` from the `UserProfile` (age/lang/country/
  interests/limits), passes recent signatures for anti-repeat, calls
  `ChallengeRepository.generate`, then runs the result through
  `FilterChallengeUseCase` (moderation/limits gate) with bounded retries
  (`DEFAULT_MAX_ATTEMPTS = 3`), recording served challenges on success.

**Matchmaking (`usecase/matchmaking/`)**

- **`MatchmakingScorer`** — pure 0.0–1.0 compatibility score with weighted
  components summing to 1.0: language 0.30, interests 0.25 (Jaccard), level 0.15,
  age 0.10, country 0.10, ping 0.10. Deterministic, so the whole ranking is
  unit-testable ("matchmaking intelligent").
- **`FormMatchUseCase`** — applies preference hard-filters (same language/
  country, adult-only, level gap, ping ceiling, age gap), scores survivors with
  `MatchmakingScorer`, greedily takes the top candidates up to the desired group
  size, and returns `null` if it cannot reach `Room.MIN_PLAYERS`. Tie-breaks by
  uid for determinism.
- **`FindMatchUseCase`** — pulls `MatchmakingRepository.candidatePool` and runs
  `FormMatchUseCase` locally for instant, offline-resilient feedback (the server
  may run the same logic authoritatively).

**Moderation (`usecase/moderation/`)**

- **`ContentModerator`** — fast, deterministic, on-device *first line* of
  moderation. Tokenizes/normalizes text, matches an injected lexicon
  (`Map<ModerationCategory, Set<String>>`, default empty), detects personal-info
  leaks (email / long digit runs) by regex, and returns a
  `ModerationResult`/`ModerationVerdict` (`ALLOW`/`FLAG`/`BLOCK`). `CRITICAL`
  categories (e.g. minors/hate/illegal/self-harm) always force `BLOCK`. It is
  *defense-in-depth*, not a replacement for server moderation.
- **`FilterChallengeUseCase`** — gate applied to an AI challenge *before* it is
  shown: enforces per-user `ContentLimits` (adult/truth/dare allowed, blocked
  categories, max difficulty), the player's age, then a final `ContentModerator`
  pass. Returns the challenge or a typed `DomainError` (`Underage`/`Moderation`)
  so the caller can transparently request a replacement.

**Progression (`usecase/progression/`)**

- **`LevelSystem`** — deterministic, closed-form XP ↔ level mapping. XP to reach
  a level: `xpToReach(L) = 25·(L−1)·(L+2)` (thresholds 0, 100, 250, 450, 700,
  1000, …), invertible in O(1) up to `MAX_LEVEL = 100`. Provides `levelForXp`,
  `progressFor` (`LevelProgress`), and `applyXp`.
- **`AwardXpUseCase`** — computes the `XpAward` for an `XpReason`; completed dares
  scale with voted difficulty (1.0× at d1 → 2.0× at d5, +0.25/step).
- **`EvaluateBadgesUseCase`** — given `PlayerStats`, returns only the **newly**
  earned `BadgeId`s (qualified minus already-unlocked). Time-of-day badges are
  awarded server-side.
- **`ApplyTurnRewardsUseCase`** — pure aggregation of *all* progression effects
  of a resolved turn: bumps lifetime counters, grants XP via `AwardXpUseCase`,
  recomputes the level via `LevelSystem`, unlocks badges via
  `EvaluateBadgesUseCase`, and returns a `TurnRewards(stats, award, newBadges)`.

Because the pure use cases take only other pure collaborators (no Android, no
I/O), they are tested directly with plain JUnit — see `domain/src/test/...`
(`GameStateMachineTest`, `TurnRotationTest`, `AggregateDifficultyUseCaseTest`,
`ComputeVideoGridLayoutUseCaseTest`, `MatchmakingScorerTest`,
`FormMatchUseCaseTest`, `ContentModeratorTest`, `FilterChallengeUseCaseTest`,
`LevelSystemTest`, `AwardXpUseCaseTest`, `EvaluateBadgesUseCaseTest`,
`ApplyTurnRewardsUseCaseTest`, `RequestChallengeUseCaseTest`,
`ModelInvariantsTest`).

---

## 3. The data layer (`:data`)

Package root: `com.actionverite.live.data`. This layer **implements** the domain
repository interfaces using Firebase and WebRTC, and wires everything with Hilt.

### 3.1 Repository implementations (`repository/`)

One `*RepositoryImpl` per domain interface: `AuthRepositoryImpl`,
`UserRepositoryImpl`, `RoomRepositoryImpl`, `GameRepositoryImpl`,
`ChallengeRepositoryImpl`, `MatchmakingRepositoryImpl`, `FriendRepositoryImpl`,
`ChatRepositoryImpl`, `LeaderboardRepositoryImpl`, `ModerationRepositoryImpl`,
`NotificationRepositoryImpl`, plus `webrtc/WebRtcRepositoryImpl`. Each is
`@Singleton @Inject constructor(...)` and returns domain models /
`DomainResult`, never leaking Firebase types upward.

Representative example — **`GameRepositoryImpl`**:
- Reads/writes the authoritative `GameSession` document at
  `rooms/{roomId}/game/state`.
- `observeSession()` is a Firestore snapshot `Flow` (via the `asFlow()` helper)
  mapped through DTOs to the domain model.
- Every transition is an **atomic read-modify-write inside a Firestore
  transaction**: it reads the current session, applies the **shared
  `GameStateMachine`** reducer (the exact same domain code that runs in tests),
  and writes the next state. This prevents lost updates from concurrent players.
  The identical rules are mirrored in Cloud Functions for server-side anti-cheat.

### 3.2 Remote helpers (`remote/`)

- **`FirestorePaths`** — single source of truth for all collection/document
  paths and field names (`users`, `rooms`, `matchQueue`, `leaderboards`,
  sub-collections `game/state`, `signals`, `messages`, `served`, …). Keeps the
  schema consistent across repositories and the security rules.
- **`FunctionsService`** — thin client over the secured Cloud Functions backend
  (`generateChallenge`, `moderateText`, `requestMatch`). The AI model API key and
  the heavy moderation/matchmaking logic live server-side; the app only calls
  authenticated, App-Check-enforced callables.
- **`dto/Dtos.kt` + `dto/Mappers.kt`** — Firestore-friendly DTOs and
  `toDomain()` / `toDto()` mappers, so wire-format concerns never bleed into the
  domain models.

### 3.3 Common helpers (`common/`)

`FirestoreFlows.kt` (`DocumentReference.asFlow()` / `Query.asFlow()` as cold
`callbackFlow`s that remove their snapshot listener on cancellation),
`FirebaseExt.kt` (e.g. `firebaseCall { }` wrapping a suspend call into
`DomainResult`), `AuthFlows.kt`, `Dispatchers.kt` (`AndroidDispatcherProvider`),
`ForegroundActivityProvider.kt` (reaches the current Activity for Firebase phone
verification — implemented in `:app` by `ActivityTracker` and bound via
`AppModule`).

### 3.4 WebRTC mesh + Firestore signaling (`webrtc/`)

The video transport uses a **full-mesh** topology: for a room of N players each
peer holds N−1 `PeerConnection`s and sends its media directly to every other
peer. This is appropriate for the spec's small 2–6 player rooms (for larger
calls this would move to an SFU).

- **`WebRtcRepositoryImpl`** (implements `RtcRepository`) owns the
  `PeerConnectionFactory`, the local camera/mic tracks (`Camera2Enumerator`,
  720p30 capture, front camera preferred), and one `PeerConnection` per remote
  peer. It exposes remote `MediaTrackRef`s and per-peer `RtcStats` as
  `StateFlow`s. The shared WebRTC `EglBase` is provided as a Hilt singleton.
  RTC config uses Unified Plan + MAX_BUNDLE with a STUN server (TURN credentials
  fetched from the backend in production).
- **`SignalingClient`** — **serverless signaling over Firestore**. SDP
  offers/answers and ICE candidates are written to `rooms/{roomId}/signals` and
  streamed back to each peer with `whereEqualTo("to", selfUid)`. This avoids a
  dedicated socket server while the actual A/V flows peer-to-peer. A deterministic
  offerer is chosen by uid ordering (perfect-negotiation pattern) to avoid glare.

```
   Signaling (control plane)            Media (data plane)
   over Firestore                       peer-to-peer (full mesh)

      Peer A ──SDP/ICE──┐                  Peer A ═══════ Peer B
                        ▼                     ║   ╲     ╱   ║
              rooms/{id}/signals              ║    ╲   ╱    ║
                        ▲                     ║     ╳       ║
      Peer B ──SDP/ICE──┘                     ║    ╱   ╲    ║
                                            Peer C ═══════ Peer D
```

### 3.5 Hilt DI in `:data`

Hilt wires the whole graph in the `SingletonComponent`:

- **`RepositoryModule`** (`@Module`, abstract) uses **`@Binds`** (not
  `@Provides`) to map each domain interface to its constructor-injected impl with
  zero boilerplate — e.g. `bindGameRepository(impl: GameRepositoryImpl):
  GameRepository`, `bindRtcRepository(impl: WebRtcRepositoryImpl): RtcRepository`,
  and `bindDispatcherProvider(AndroidDispatcherProvider): DispatcherProvider`.
  All bindings are `@Singleton`.
- **`FirebaseModule`** (`@Module object`, `@Provides`) provides the Firebase
  singletons — `FirebaseAuth`, `FirebaseFirestore` (offline persistence enabled),
  `FirebaseFunctions`, `FirebaseStorage`, `FirebaseMessaging` — and the shared
  `EglBase` for WebRTC.
- **`DomainModule`** (`@Module object`, `@Provides`) constructs the one domain
  collaborator that needs explicit arguments: `ContentModerator(lexicon = ...)`
  (Hilt ignores Kotlin default parameter values, and the lexicon is loaded from
  Remote Config in production). Every other use case is plain `@Inject`-
  constructed and needs no module entry.

The host `:app` module adds **`AppModule`**, which binds the app-side
`ActivityTracker` to the data-layer `ForegroundActivityProvider` interface, and
exposes an `@EntryPoint` so `ActionVeriteApp` can register the tracker. This
keeps the data layer's need ("give me the foreground Activity") expressed as an
interface while the only thing that can satisfy it (an `Application` lifecycle
callback) lives in `:app` — dependency inversion across the module boundary.

---

## 4. The presentation layer (`:app`)

Package root: `com.actionverite.live`. UI lives under `com.actionverite.live.ui`
and per-screen features under `com.actionverite.live.feature.<name>`. The app
uses Jetpack Compose with Material 3 (`ActionVeriteTheme` in `ui/theme/`),
`navigation-compose`, Coil, and `accompanist-permissions`.

### 4.1 Composition root and startup

- **`ActionVeriteApp`** — `@HiltAndroidApp`; triggers Hilt code generation,
  configures Crashlytics, creates notification channels, and registers the
  `ActivityTracker`.
- **`MainActivity`** — `@AndroidEntryPoint`; installs the splash screen, observes
  `AppViewModel.rootState` and chooses the navigation start destination.
- **`AppViewModel`** — combines `AuthRepository.authState` with
  `UserRepository.observeCurrentProfile()` via `flatMapLatest` into a top-level
  `RootState` (`Loading`/`Unauthenticated`/`NeedsOnboarding`/`Ready`), held as a
  `StateFlow` with `stateIn(... WhileSubscribed(5_000) ...)`. The splash is kept
  on screen until the state resolves.

### 4.2 Navigation

`ui/navigation/AvNavHost.kt` is the **single** navigation graph and the only code
that knows the navigation framework. Each feature exposes exactly one entry
composable `*Route(... callbacks ...)`; the graph supplies those callbacks
(`navController.navigate(...)`, `popBackStack()`), so features stay decoupled
from navigation and from each other. `ui/navigation/Destinations.kt` centralizes
route strings and argument keys (e.g. the `GAME` route's `roomId` arg).

### 4.3 MVVM with unidirectional data flow

Every feature follows the same pattern (canonical templates:
`feature/profile/ProfileViewModel.kt` + `ProfileScreen.kt` for stream-driven
state, `feature/auth/AuthViewModel.kt` + `AuthScreen.kt` for one-shot events):

1. A `data class <Feature>UiState(...)` with sensible defaults — the **single**
   immutable description of what the screen shows.
2. A `@HiltViewModel class <Feature>ViewModel @Inject constructor(... domain
   repositories / use cases ...) : ViewModel()`.
   - For stream-driven screens, `uiState` is a `StateFlow` built by mapping a
     repository `Flow` and `stateIn(viewModelScope,
     SharingStarted.WhileSubscribed(5_000), default)` (see `ProfileViewModel`
     mapping `observeCurrentProfile()` + `LevelSystem.progressFor`).
   - For action-driven screens, a private `MutableStateFlow` is updated inside
     `viewModelScope.launch { ... }`, exposed via `asStateFlow()` (see
     `AuthViewModel`).
   - Suspend repository/use-case calls return `DomainResult`, handled with an
     exhaustive `when (Success/Failure)`.
   - **One-shot** navigation side-effects are emitted through a
     `Channel(Channel.BUFFERED)` exposed as `events = _events.receiveAsFlow()`
     (see `AuthEvent` in `AuthViewModel`), or passed as callback lambdas — never
     stored in `UiState`, so they fire exactly once.
3. A public `@Composable <Feature>Route(...the exact signature AvNavHost calls)`
   that obtains the ViewModel with `hiltViewModel()`, reads
   `val ui by viewModel.uiState.collectAsStateWithLifecycle()`, wires callbacks
   (and collects `events` with a `LaunchedEffect` when present), then delegates
   to a private, **stateless** `@Composable <Feature>Screen(ui, onX, onY, …)`
   that is trivially previewable and testable.

This is **unidirectional data flow**: state flows *down* (ViewModel →
`UiState` → stateless `Screen`), events flow *up* (user taps → `Route` callback
→ ViewModel method → repository/use case → new `UiState`). The stateless `Screen`
holds no business logic.

```
   ┌────────────┐   state (UiState)   ┌──────────────────┐  state  ┌──────────────┐
   │ Repository │ ───── Flow ───────▶ │  <Feature>VM      │ ──────▶ │ <Feature>Route│
   │ / UseCase  │ ◀── suspend calls ─ │ StateFlow<UiState>│        │  (hiltViewModel)│
   └────────────┘     (DomainResult)  │  Channel<Event>   │        └──────┬────────┘
        ▲                             └──────────────────┘   state ↓      │ events
        │                                       ▲                  ┌───────▼────────┐
        │           events (callbacks)          │ user intent      │ <Feature>Screen │
        └───────────────────────────────────────┴──────────────── │  (stateless)    │
                                                                   └────────────────┘
```

### 4.4 Features

`feature/auth`, `feature/onboarding`, `feature/home`, `feature/matchmaking`,
`feature/game` (with `VideoGrid.kt` rendering the `ComputeVideoGridLayoutUseCase`
result over WebRTC tracks), `feature/friends`, `feature/leaderboard`,
`feature/profile`, `feature/settings`. Shared, reusable Compose building blocks
(`PrimaryButton`, `SecondaryButton`, `Avatar`, `FullScreenLoading`, `ErrorState`)
live in `ui/components/CommonComponents.kt`. Push notifications are handled by
`notification/AvFirebaseMessagingService`.

---

## 5. End-to-end example: one game turn

Putting the layers together for a single dare turn:

1. **UI** — the active player taps "Dare" in `GameScreen`; the `Route` callback
   calls `GameViewModel.chooseType(DARE)`.
2. **ViewModel** — calls `GameRepository.chooseType(roomId, DARE)` in
   `viewModelScope`.
3. **Data** — `GameRepositoryImpl` runs a Firestore transaction that applies
   `GameStateMachine.reduce(session, ChooseType(DARE))` → phase
   `COLLECTING_VOTES`.
4. **Domain (rules)** — other players cast 1–5 votes (`CastVote`); `CloseVoting`
   runs `AggregateDifficultyUseCase` to a `DifficultyResult`.
5. **AI** — `RequestChallengeUseCase` builds a personalized `ChallengeRequest`,
   calls `ChallengeRepository.generate` (→ `FunctionsService.generateChallenge`),
   and gates the result through `FilterChallengeUseCase` + `ContentModerator`.
6. **Media** — `RtcRepository`/`WebRtcRepositoryImpl` carries the live video as
   the player performs; `submitOutcome(completed)` moves to `RATING`.
7. **Progression** — `ApplyTurnRewardsUseCase` updates `PlayerStats`, grants XP
   (`AwardXpUseCase` + `LevelSystem`) and unlocks badges
   (`EvaluateBadgesUseCase`), persisted via `UserRepository.updateStats`.
8. **Advance** — `NextTurn` (via `TurnRotation`) skips disconnected players and
   loops back to step 1, or `FINISHED` when rounds are exhausted.

Every state observed by the UI flows back through the repository `Flow`s into
each feature's `UiState`.

---

## 6. Testability

The architecture is optimized for fast, deterministic testing:

- **Pure domain.** `:domain` is a plain JVM module with no Android/Firebase
  dependencies, so its use cases run as ordinary JUnit tests with no emulator,
  no Robolectric and no mocking framework required for the pure ones. The full
  rule-set — turn lifecycle, difficulty aggregation, rotation, grid layout,
  matchmaking scoring/forming, moderation gating, XP/levels/badges/turn-rewards —
  is covered under `domain/src/test/...`.
- **Boundaries are interfaces.** Because the domain depends only on repository
  *interfaces*, any test (or a `@Preview`) can substitute a fake implementation
  without Firebase. The same indirection lets the production graph swap Firebase
  for another backend without touching the domain or the UI.
- **Injected dispatchers.** `DispatcherProvider` keeps coroutine code testable —
  inject a test dispatcher; no hidden `Dispatchers.IO`.
- **Stateless Composables.** Each `<Feature>Screen` is a pure function of
  `UiState` + lambdas, so it is rendered in Compose UI tests / previews with
  hand-built state and needs no ViewModel or Hilt graph.
- **Shared rules on client and server.** The `GameStateMachine` that drives
  Firestore transactions in `GameRepositoryImpl` is the exact code the tests
  exercise, and the same rules are mirrored server-side in Cloud Functions for
  anti-cheat — one auditable definition of correctness.
