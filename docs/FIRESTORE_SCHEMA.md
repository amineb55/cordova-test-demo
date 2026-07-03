# Cloud Firestore Schema — Action ou Vérité Live

This document is the authoritative reference for the app's Firestore data model. It is
derived field-for-field from the data layer and **must stay in sync** with:

- Paths: `data/src/main/kotlin/com/actionverite/live/data/remote/FirestorePaths.kt`
- DTOs: `data/src/main/kotlin/com/actionverite/live/data/remote/dto/Dtos.kt`
- Signaling shape: `data/src/main/kotlin/com/actionverite/live/data/webrtc/SignalingClient.kt`
- Security rules: `firestore.rules`

Conventions used in this document:

- **Type** is the Firestore wire type (`string`, `number`, `boolean`, `map`, `array<…>`,
  `timestamp`). Kotlin `Long`/`Int`/`Double` all serialize as Firestore `number`.
- "Nullable" means the DTO field is `T?` (may be absent / `null`).
- All `*EpochMs` / `*AtEpochMs` fields are **client-supplied `number`** (Unix epoch
  milliseconds), *not* Firestore `timestamp`. This is deliberate so the on-device game
  loop can reason about times without round-tripping server timestamps.
- Every rule below requires an authenticated caller (`request.auth != null`); the model
  is **deny-by-default** via the terminal `match /{document=**} { allow read, write: if false; }`.

---

## Top-level map

| Path | Doc id | DTO / shape | Rule block |
|------|--------|-------------|------------|
| `users/{uid}` | Firebase Auth uid | `UserDto` | `match /users/{uid}` |
| `users/{uid}/friends/{friendUid}` | friend uid | `FriendDto` | `match /users/{uid}/friends/{friendUid}` |
| `users/{uid}/served/{signature}` | challenge signature | `ChallengeDto` | `match /users/{uid}/served/{signature}` |
| `users/{uid}/dm/{peerUid}` | peer uid | (container only) | `match /users/{uid}/dm/{peerUid}` |
| `users/{uid}/dm/{peerUid}/messages/{msgId}` | auto id | `ChatMessageDto` | `match /users/{uid}/dm/{peerUid}/messages/{msgId}` |
| `rooms/{roomId}` | room id | `RoomDto` (embeds `List<PlayerDto>`) | `match /rooms/{roomId}` |
| `rooms/{roomId}/game/state` | literal `"state"` | `GameSessionDto` | `match /rooms/{roomId}/game/{doc}` |
| `rooms/{roomId}/signals/{auto}` | auto id | `SignalMessage` | `match /rooms/{roomId}/signals/{peer}` |
| `rooms/{roomId}/messages/{msgId}` | auto id | `ChatMessageDto` | `match /rooms/{roomId}/messages/{msgId}` |
| `matchQueue/{uid}` | Auth uid | queue map (see below) | `match /matchQueue/{uid}` |
| `leaderboards/{…}` | materialized | `LeaderboardEntryDto` | `match /leaderboards/{document=**}` |
| `reports/{reportId}` | auto id | `ReportDto` | `match /reports/{reportId}` |
| `friendRequests/{requestId}` | auto id | `FriendRequestDto` | `match /friendRequests/{requestId}` |

> The path constants `PLAYERS`, `GAME`, `GAME_STATE_DOC`, etc. live in `FirestorePaths`.
> Note that **`players` is an embedded array on the room document**, not a subcollection —
> `FirestorePaths.PLAYERS` names the array field, and the security rules read it via
> `room.players` (see `roomContainsCaller`).

---

## `users/{uid}` — public profile (`UserDto`)

Document id is the Firebase Auth uid. Readable by any signed-in user (profiles, friend
lookups, leaderboards); writable only by the owner.

| Field | Type | Notes |
|-------|------|-------|
| `uid` | string | Mirrors the document id. |
| `displayName` | string | Public name. |
| `photoUrl` | string? | Avatar URL (Cloud Storage / provider). Nullable. |
| `email` | string? | Nullable; present only for email/password & some providers. |
| `phoneNumber` | string? | Nullable; phone-auth users. |
| `providers` | array&lt;string&gt; | Linked auth providers (e.g. `google.com`, `phone`). |
| `languageCode` | string | ISO-639-1, default `"en"`. Indexed for matchmaking-style queries. |
| `countryCode` | string | ISO-3166 alpha-2, default `"US"`. |
| `birthEpochDay` | number? | Birth date as epoch **day** (not ms). Nullable; used for age gating. |
| `interests` | array&lt;string&gt; | `Interest` enum names. |
| `limits` | map (`LimitsDto`) | Embedded content prefs (see below). |
| `stats` | map (`StatsDto`) | Embedded progression/counters (see below). |
| `createdAtEpochMs` | number | Account creation, epoch ms. |

### `limits` map (`LimitsDto`)

| Field | Type | Notes |
|-------|------|-------|
| `allowTruth` | boolean | Default `true`. |
| `allowDare` | boolean | Default `true`. |
| `allowAdult` | boolean | Default `false`. Drives adult-content gating. |
| `maxDifficulty` | number | 1–5, default `5`. |
| `blockedCategories` | array&lt;string&gt; | `ChallengeCategory` enum names the user opted out of. |

### `stats` map (`StatsDto`)

| Field | Type | Notes |
|-------|------|-------|
| `xp` | number | Total XP. **Leaderboard sort key** (`stats.xp`). Server-awarded. |
| `level` | number | Derived level, default `1`. |
| `gamesPlayed` | number | Counter. |
| `truthsAnswered` | number | Counter. |
| `daresCompleted` | number | Counter. |
| `daresFailed` | number | Counter. |
| `votesCast` | number | Difficulty-vote counter. |
| `friendsCount` | number | Denormalized friend count (avoids counting the `friends` subcollection). |
| `maxDifficultyCompleted` | number | Highest difficulty cleared. |
| `unlockedBadges` | array&lt;string&gt; | Badge ids. |

**Access pattern**

- Read own/other profile: `users.document(uid).get()` (`UserRepositoryImpl`).
- Leaderboard: `users.orderBy("stats.xp", DESCENDING).limit(n)` (`LeaderboardRepositoryImpl.observe`).
- "My rank": `users.whereGreaterThan("stats.xp", myXp).limit(10000).size()` then `ahead + 1`.

**Rule** — `match /users/{uid}`: `read: if signedIn()`; `create/update/delete: if isOwner(uid)`.

> Security note (from `firestore.rules`): `stats.xp` and the leaderboard counters are meant
> to be awarded server-side. The current owner-write rule lets a client mutate its own
> `stats`. Production should require `request.resource.data.stats == resource.data.stats`
> on client updates and mutate `stats` only from a Cloud Function (Admin SDK).

---

## `users/{uid}/friends/{friendUid}` — friend list (`FriendDto`)

Subcollection of the owner. Document id is the friend's uid. Owner-only read/write — the
reciprocal stub on the *other* user's document is written by the accept flow (a batch or
Cloud Function), never as a direct cross-user write.

| Field | Type | Notes |
|-------|------|-------|
| `uid` | string | Friend's uid (mirrors doc id). |
| `displayName` | string | **Denormalized** from the friend's profile for list rendering without N reads. |
| `photoUrl` | string? | Denormalized avatar. Nullable. |
| `level` | number | Denormalized, default `1`. |
| `status` | string | `FriendStatus` enum name, default `"ACCEPTED"`. |
| `presence` | string | `Presence` enum name, default `"OFFLINE"`. |
| `countryCode` | string? | Nullable. |

**Access pattern** — list/observe `users/{me}/friends`; written on accept and refreshed on presence change.

**Rule** — `match /users/{uid}/friends/{friendUid}`: `read, write: if isOwner(uid)`.

---

## `users/{uid}/served/{signature}` — served-challenge dedupe (`ChallengeDto`)

Records which AI challenges a user has already seen so they are not repeated. **Document id
is the challenge `signature`** (falling back to `challenge.id` when blank — see
`ChallengeRepositoryImpl`). The full `ChallengeDto` is stored as the document body; reads
typically only pull the `signature` field.

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | Challenge id. |
| `type` | string | `ChallengeType` enum name, default `"TRUTH"`. |
| `text` | string | Challenge prompt. |
| `category` | string | `ChallengeCategory` enum name, default `"FUNNY"`. |
| `difficulty` | number | 1–5, default `1`. |
| `languageCode` | string | Default `"en"`. |
| `isAdult` | boolean | Default `false`. |
| `signature` | string | Content hash; **mirrors the doc id** (dedupe key). |
| `generatedAtEpochMs` | number | Generation time, epoch ms. |

**Access pattern** — read signatures: `served.get()` → `getString("signature")`; mark served:
`served.document(sig).set(challenge.toDto())`.

**Rule** — `match /users/{uid}/served/{signature}`: `read, write: if isOwner(uid)`.

---

## `users/{uid}/dm/{peerUid}/messages/{msgId}` — direct messages (`ChatMessageDto`)

Each user stores **their own copy** of a DM thread under `dm/{peerUid}/messages`. Sending a
DM is a **dual-write fan-out** (`ChatRepositoryImpl`): the message is written to both
`users/{me}/dm/{peer}/messages/{id}` and `users/{peer}/dm/{me}/messages/{id}` using the
**same generated `msgId`** so the two copies stay aligned. The intermediate
`users/{uid}/dm/{peerUid}` document is a container only (no schema of its own).

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | Message id (mirrors doc id; identical across both copies). |
| `senderUid` | string | Author uid. |
| `senderName` | string | **Denormalized** sender name. |
| `text` | string | Message body. |
| `sentAtEpochMs` | number | Sent time, epoch ms. **Order key** for the thread. |
| `moderated` | boolean | Default `false`; set true if filtered. |

**Access pattern** — observe `users/{me}/dm/{peer}/messages` ordered by `sentAtEpochMs`; send = batched dual-write.

**Rule** — both `match /users/{uid}/dm/{peerUid}` and `.../messages/{msgId}`:
`read, write: if isOwner(uid)`. Because each side owns its own copy, no cross-user write rule
is needed; the peer's copy is authored under *the peer's* path (production should move the
peer-side write into a Cloud Function so it is not a direct client write to another user's tree).

---

## `rooms/{roomId}` — game room (`RoomDto`)

The lobby/room document. Any signed-in user may read it (lobby browsing / spectating).
**`players` is an embedded `array<map>` of `PlayerDto`** on this document, not a subcollection.

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | Mirrors doc id. |
| `hostUid` | string | Room owner. Required to equal caller on create; required for delete. |
| `visibility` | string | `RoomVisibility` enum name, default `"PUBLIC"`. (`FIELD_VISIBILITY`) |
| `status` | string | `RoomStatus` enum name, default `"WAITING"`. (`FIELD_STATUS`) |
| `players` | array&lt;map&gt; (`PlayerDto`) | Embedded roster (see below). |
| `languageCode` | string | Default `"en"`. |
| `countryCode` | string? | Nullable. |
| `adultOnly` | boolean | Default `false`. |
| `inviteCode` | string? | Nullable; for private/invite rooms. |
| `maxPlayers` | number | 2–6, default `6`. |
| `createdAtEpochMs` | number | Epoch ms. |

### Embedded `players[]` element (`PlayerDto`)

| Field | Type | Notes |
|-------|------|-------|
| `uid` | string | Player uid. |
| `displayName` | string | **Denormalized** name. |
| `photoUrl` | string? | Denormalized avatar. Nullable. |
| `level` | number | Denormalized, default `1`. |
| `micEnabled` | boolean | Default `true`. |
| `cameraEnabled` | boolean | Default `true`. |
| `speaking` | boolean | Default `false`; live VAD flag. |
| `pingMs` | number | Connection latency. |
| `connection` | string | `"GOOD"` / quality enum, default `"GOOD"`. |
| `isHost` | boolean | Default `false`. Used by the membership rule. |
| `joinedAtEpochMs` | number | Epoch ms. |

**Access pattern** — `rooms.document(id)` read/observe; join/leave/toggle-media via transactional
update of the `players` array (`RoomRepositoryImpl`); host deletes the room.

**Rule** — `match /rooms/{roomId}`:
`read: if signedIn()`; `create: if signedIn() && request.resource.data.hostUid == request.auth.uid`;
`update: if signedIn()` (the floor — a *new* joiner is not yet a member, so per-field integrity
is enforced in the repository transaction; production moves authoritative mutations such as
`status` and evictions behind a Cloud Function); `delete: if signedIn() && resource.data.hostUid == request.auth.uid`.

The helper `roomContainsCaller(roomId)` reads `rooms/{roomId}.players` and checks the caller is
`hostUid` or appears as `{'uid': caller, 'isHost': true|false}`; it gates the room subcollections.

---

## `rooms/{roomId}/game/state` — authoritative game session (`GameSessionDto`)

A **single document** with the literal id `"state"` (`FirestorePaths.GAME_STATE_DOC`) under the
`game` subcollection. Holds the entire turn-based game state for the on-device loop.

| Field | Type | Notes |
|-------|------|-------|
| `roomId` | string | Owning room. |
| `phase` | string | `GamePhase` enum name, default `"LOBBY"`. |
| `turnOrder` | array&lt;string&gt; | Player uids in turn order. |
| `activeIndex` | number | Index into `turnOrder`, default `0`. |
| `round` | number | Current round, default `0`. |
| `maxRounds` | number | Default `10`. |
| `currentTurn` | map (`TurnDto`)? | The in-progress turn (see below). Nullable. |
| `history` | array&lt;map&gt; (`TurnDto`) | Completed turns. |
| `startedAtEpochMs` | number | Epoch ms. |

### `TurnDto` (used for `currentTurn` and each `history[]` entry)

| Field | Type | Notes |
|-------|------|-------|
| `index` | number | Turn index. |
| `activeUid` | string | Whose turn it is. |
| `type` | string? | `ChallengeType` chosen (`TRUTH`/`DARE`). Nullable until chosen. |
| `votes` | array&lt;map&gt; (`DifficultyVoteDto`) | Per-voter difficulty votes. |
| `difficulty` | map (`DifficultyResultDto`)? | Aggregated vote result. Nullable. |
| `challenge` | map (`ChallengeDto`)? | The served challenge. Nullable. See `served` table for fields. |
| `outcome` | string | `TurnOutcome` enum name, default `"PENDING"`. |

### `DifficultyVoteDto` (element of `votes[]`)

| Field | Type | Notes |
|-------|------|-------|
| `voterUid` | string | Voter. |
| `value` | number | 1–5, default `3`. |

### `DifficultyResultDto` (`currentTurn.difficulty`)

| Field | Type | Notes |
|-------|------|-------|
| `average` | number | Mean vote (double), default `3.0`. |
| `target` | number | Resolved difficulty, default `3`. |
| `voteCount` | number | Votes counted. |

**Access pattern** — observe `rooms/{id}/game/state`; players write turn transitions via the
game loop (`GameRepositoryImpl`).

**Rule** — `match /rooms/{roomId}/game/{doc}`: `read: if signedIn()`; `write: if roomContainsCaller(roomId)`.

> Security note: this is the most security-sensitive write. Clients can currently forge
> turns/outcomes. Production should set `allow write: if false;` and own all transitions from a
> Cloud Function (Admin SDK).

---

## `rooms/{roomId}/signals/{auto}` — WebRTC signaling (`SignalMessage`)

Serverless SDP/ICE exchange between room participants. Documents are added with auto ids and
addressed by the `to` field. Defined in `SignalingClient` (not in `Dtos.kt`).

| Field | Type | Notes |
|-------|------|-------|
| `type` | string | `OFFER` / `ANSWER` / `CANDIDATE`. |
| `from` | string | Sender uid. **Queried** in `clear()` (`whereEqualTo("from", self)`). |
| `to` | string | Recipient uid. **Queried** in `incoming()` (`whereEqualTo("to", self)`). |
| `sdp` | string? | Session description (offers/answers). Nullable. |
| `sdpMid` | string? | ICE candidate mid. Nullable. |
| `sdpMLineIndex` | number? | ICE candidate m-line index. Nullable. |
| `candidate` | string? | ICE candidate string. Nullable. |
| `createdAt` | number | Epoch ms, set on `send()`. |

**Access pattern** — `incoming(roomId, self)`: `signals.whereEqualTo("to", self)`; `send()` adds a doc;
`clear(roomId, self)`: query `whereEqualTo("from", self)` then delete each.

**Rule** — `match /rooms/{roomId}/signals/{peer}`: `read: if signedIn()`; `write: if roomContainsCaller(roomId)`.

---

## `rooms/{roomId}/messages/{msgId}` — in-room chat (`ChatMessageDto`)

Same DTO as DMs. Auto-id documents under the room. Schema identical to the
[DM messages table](#usersuiddmpeeruidmessagesmsgid--direct-messages-chatmessagedto).

**Access pattern** — observe `rooms/{id}/messages` ordered by `sentAtEpochMs`; post via add.

**Rule** — `match /rooms/{roomId}/messages/{msgId}`:
`read: if signedIn()`; `create: if roomContainsCaller(roomId) && request.resource.data.senderUid == request.auth.uid`;
`update, delete: if signedIn() && resource.data.senderUid == request.auth.uid`.

---

## `matchQueue/{uid}` — matchmaking pool (queue map)

**No DTO** — the entry is a hand-built `Map<String, Any?>` written by
`MatchmakingRepositoryImpl.toQueueMap`. Document id is the caller's uid (one entry per user).
The matchmaker runs in a Cloud Function (`FunctionsService.requestMatch`) with the Admin SDK,
which can read the whole pool; clients can only see their own entry.

| Field | Type | Notes |
|-------|------|-------|
| `uid` | string | Mirrors doc id; required to equal caller on write. |
| `languageCode` | string | Self language. |
| `countryCode` | string | Self country. |
| `age` | number? | Nullable (derived from `birthEpochDay`). |
| `level` | number | Self level. |
| `interests` | array&lt;string&gt; | `Interest` enum names. |
| `allowAdult` | boolean | Adult-content opt-in. |
| `pingMs` | number | Reported latency. |
| `desiredSize` | number | From `MatchPreferences.desiredSize`. |
| `enqueuedAt` | number | Epoch ms; **wait-time / staleness key** for the matcher. |

**Access pattern** — enqueue: `matchQueue.document(self.uid).set(map)` then call `requestMatch`;
client-side candidate snapshot: `matchQueue.limit(50).get()`; cancel: `matchQueue.document(uid).delete()`.

**Rule** — `match /matchQueue/{uid}`:
`read: if isOwner(uid)`; `create, update: if isOwner(uid) && request.resource.data.uid == request.auth.uid`;
`delete: if isOwner(uid)`. (The Admin SDK in the Cloud Function bypasses these to scan the pool.)

---

## `leaderboards/{…}` — materialized rankings (`LeaderboardEntryDto`)

Read-only to clients; **written exclusively by a scheduled Cloud Function** (Admin SDK). The
recursive `{document=**}` match allows for period/scope partitioning (e.g.
`leaderboards/global/entries/{uid}`, `leaderboards/{country}/entries/{uid}`). Each leaf entry
is a `LeaderboardEntryDto`.

> Note: the *current* live leaderboard in `LeaderboardRepositoryImpl` is computed directly from
> `users` ordered by `stats.xp` (see composite-index section). The `leaderboards` collection is
> the cost-optimized, function-materialized cache for the same data and uses this DTO.

| Field | Type | Notes |
|-------|------|-------|
| `uid` | string | Player uid (typically the leaf doc id). |
| `displayName` | string | **Denormalized** name. |
| `photoUrl` | string? | Denormalized avatar. Nullable. |
| `level` | number | Denormalized, default `1`. |
| `xp` | number | Sort key (denormalized from `users.stats.xp`). |
| `countryCode` | string? | Nullable; enables national scoping. |

**Access pattern** — read materialized entries by scope/period; never written by clients.

**Rule** — `match /leaderboards/{document=**}`: `read: if signedIn()`; `write: if false` (Admin SDK only).

---

## `reports/{reportId}` — moderation reports (`ReportDto`)

Create-only, write-once. **Never client-readable** (moderation tooling uses the Admin SDK).

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | Mirrors doc id. |
| `reporterUid` | string | Author; required to equal caller on create. |
| `targetUid` | string | Reported user. |
| `roomId` | string? | Optional context. Nullable. |
| `reason` | string | `ReportReason` enum name, default `"OTHER"`. |
| `details` | string? | Free-text detail. Nullable. |
| `createdAtEpochMs` | number | Epoch ms. |

**Access pattern** — `reports.add(report)` (`ModerationRepositoryImpl`); reads only via Admin tooling.

**Rule** — `match /reports/{reportId}`:
`read: if false`; `create: if signedIn() && request.resource.data.reporterUid == request.auth.uid`;
`update, delete: if false`.

---

## `friendRequests/{requestId}` — pending friend requests (`FriendRequestDto`)

Auto-id documents. The sender creates (`fromUid == caller`); both sender and recipient may read
and delete; requests are **immutable** (accept = delete + a batched write of reciprocal
`friends` stubs).

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | Mirrors doc id. |
| `fromUid` | string | Sender; required to equal caller on create. |
| `toUid` | string | Recipient; required `!= caller` on create. |
| `fromDisplayName` | string | **Denormalized** sender name for the incoming-request list. |
| `createdAtEpochMs` | number | Epoch ms. |

**Access pattern** — incoming list: `whereEqualTo("toUid", me)`; outgoing list:
`whereEqualTo("fromUid", me)`; accept/decline/cancel = delete (`FriendRepositoryImpl`).

**Rule** — `match /friendRequests/{requestId}`:
`read: if signedIn() && (resource.data.toUid == caller || resource.data.fromUid == caller)`;
`create: if signedIn() && request.resource.data.fromUid == caller && request.resource.data.toUid != caller`;
`delete: if signedIn() && (toUid == caller || fromUid == caller)`; `update: if false`.

---

## Denormalization choices

The model is intentionally denormalized for fast, single-read list rendering, accepting that
the source of truth (`users/{uid}`) can drift until a refresh:

| Denormalized data | Lives in | Source of truth | Why |
|-------------------|----------|-----------------|-----|
| `displayName`, `photoUrl`, `level` | `FriendDto`, `PlayerDto`, `FriendRequestDto`, `LeaderboardEntryDto`, `ChatMessageDto.senderName` | `users/{uid}` profile | Render friend/player/leaderboard/chat lists without N extra profile reads. |
| `players[]` (embedded `PlayerDto` array) | `rooms/{roomId}` | per-user state | One read returns the full roster; enables the in-rule `roomContainsCaller` membership check. |
| `stats.friendsCount` | `users/{uid}` | `users/{uid}/friends` count | Avoids an aggregation/count over the friends subcollection. |
| `xp` | `LeaderboardEntryDto` | `users/{uid}.stats.xp` | Lets the materialized leaderboard sort without joining profiles. |
| `currentTurn.challenge` (`ChallengeDto`) | `rooms/{roomId}/game/state` | generated challenge | Embeds the active challenge so the whole turn is one document read. |
| DM message (dual copy) | `users/{me}/dm/{peer}` **and** `users/{peer}/dm/{me}` | the single logical message | Each user reads only their own subtree (rules stay owner-only); shared `msgId` keeps copies aligned. |

**Consistency obligation:** when a profile's `displayName` / `photoUrl` / `level` / `xp`
changes, the denormalized copies in friends lists, room player maps, friend requests and
leaderboard entries must be fanned out — ideally by a Cloud Function trigger on
`users/{uid}` writes.

---

## Composite indexes required

Single-field indexes are auto-created by Firestore; the queries below need explicit composite
or single-field-with-direction indexes (define in `firestore.indexes.json`).

| Collection | Query (source) | Fields (order) | Notes |
|------------|----------------|----------------|-------|
| `users` | `orderBy("stats.xp", DESC)` — `LeaderboardRepositoryImpl.observe` | `stats.xp` DESC | Single-field **descending** index (descending order on a nested field must be declared). |
| `users` | `whereGreaterThan("stats.xp", x)` — `myRank` | `stats.xp` ASC/range | Range scan; covered by the single-field index. |
| `users` (national) | future: `whereEqualTo("countryCode", c).orderBy("stats.xp", DESC)` | `countryCode` ASC, `stats.xp` DESC | **Composite** — needed when `LeaderboardScope.NATIONAL` is filtered server-side (currently best-effort client-side). |
| `rooms/{id}/signals` | `whereEqualTo("to", uid)` — `incoming` | `to` ASC | Single-field (auto). |
| `rooms/{id}/signals` | `whereEqualTo("from", uid)` — `clear` | `from` ASC | Single-field (auto). |
| `rooms/{id}/messages` | observe + `orderBy("sentAtEpochMs")` | `sentAtEpochMs` ASC | Single-field (auto); composite needed only if combined with a `where`. |
| `users/{uid}/dm/{peer}/messages` | observe + `orderBy("sentAtEpochMs")` | `sentAtEpochMs` ASC | Single-field (auto). |
| `friendRequests` | `whereEqualTo("toUid", me)` / `whereEqualTo("fromUid", me)` | `toUid` ASC / `fromUid` ASC | Single-field (auto); add a composite if ordered by `createdAtEpochMs`. |
| `matchQueue` | `limit(50)` snapshot; (function: filter by lang/country/`enqueuedAt`) | `languageCode` ASC, `countryCode` ASC, `enqueuedAt` ASC | **Composite** for the Cloud Function matcher's filtered/ordered pool scan. |
| `rooms` (lobby browse) | future: `whereEqualTo("visibility","PUBLIC").whereEqualTo("status","WAITING").orderBy("createdAtEpochMs", DESC)` | `visibility` ASC, `status` ASC, `createdAtEpochMs` DESC | **Composite** — needed when the public-lobby list filters by visibility+status and sorts by recency. |

> Firestore requires an explicit composite index whenever a query combines an equality (or
> range) filter with an `orderBy` on a different field, or combines multiple range/inequality
> conditions. The single-field rows above are listed for completeness and are created
> automatically.
