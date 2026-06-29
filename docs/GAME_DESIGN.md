# Game Design — Action ou Vérité Live

This document is the authoritative gameplay spec for **Action ou Vérité Live**, a
Truth‑or‑Dare game played over live WebRTC video by 2–6 players, with
AI‑generated, age/locale‑adapted challenges, difficulty voting, and a full
XP/level/badge progression system.

It mirrors the pure‑Kotlin domain layer exactly. Where a rule is encoded in code,
the source of truth is named so the two never drift:

| Concern | Source of truth (`domain/.../`) |
| --- | --- |
| Turn lifecycle / state transitions | `usecase/game/GameStateMachine.kt` |
| Turn rotation & round wrapping | `usecase/game/TurnRotation.kt` |
| Difficulty aggregation | `usecase/game/AggregateDifficultyUseCase.kt` |
| AI challenge request + moderation gate | `usecase/game/RequestChallengeUseCase.kt` |
| Content gating / limits | `usecase/moderation/FilterChallengeUseCase.kt` |
| Video grid layout | `usecase/game/ComputeVideoGridLayoutUseCase.kt` |
| XP ↔ level curve | `usecase/progression/LevelSystem.kt` |
| XP rewards | `usecase/progression/AwardXpUseCase.kt` |
| Badge unlocks | `usecase/progression/EvaluateBadgesUseCase.kt` |
| Per‑turn reward aggregation | `usecase/progression/ApplyTurnRewardsUseCase.kt` |
| Core models | `model/Game.kt`, `model/Challenge.kt`, `model/Progression.kt`, `model/VideoGrid.kt`, `model/Room.kt`, `model/UserProfile.kt`, `model/Moderation.kt` |

Player‑count bounds are global constants: `Room.MIN_PLAYERS = 2`,
`Room.MAX_PLAYERS = 6`. The difficulty scale is `Difficulty.MIN = 1`,
`Difficulty.MAX = 5`.

---

## 1. The game loop

A game is one `GameSession` (`model/Game.kt`) bound to a `roomId`. It is an
immutable snapshot; the **only** thing allowed to transition it is the pure,
deterministic reducer `GameStateMachine.reduce(session, event)`. Every transition
is driven by a `GameEvent`, and invalid transitions throw `IllegalStateException`
(treated as a protocol/programmer error, never a user‑facing failure).

High‑level flow:

```
Lobby (room fills, host can start when 2..6 present)
  │  StartGame(turnOrder)
  ▼
┌───────────────────────────── per turn ─────────────────────────────┐
│ CHOOSING_TYPE                                                       │
│   active player picks Truth or Dare                                 │
│      ├─ TRUTH ──────────────────────────────► GENERATING           │
│      └─ DARE  ──► COLLECTING_VOTES                                  │
│                     others vote difficulty 1..5                     │
│                     CloseVoting → average computed → GENERATING     │
│ GENERATING                                                          │
│   AI generates a challenge matching the difficulty (Dare) / a       │
│   Truth prompt; ChallengeReady → PERFORMING                         │
│ PERFORMING                                                          │
│   player performs on live video; SubmitOutcome(completed) → RATING  │
│ RATING                                                              │
│   outcome recorded; progression rewards applied; NextTurn          │
└────────────────────────────────────────────────────────────────────┘
  │  NextTurn rotates to the next present player.
  │  When rotation wraps past the end of turnOrder → round += 1.
  ▼
FINISHED  when round > maxRounds, OR when fewer than MIN_PLAYERS remain present.
```

`GameSession.maxRounds` defaults to `DEFAULT_MAX_ROUNDS = 10`. A "round" is one
full pass through the (present) turn order.

### 1.1 Lobby

Players gather in a `Room` (`model/Room.kt`). The room reports:

- `canStart` — true when `presentCount` is in `MIN_PLAYERS..MAX_PLAYERS` (2..6).
- `isFull` — true at `maxPlayers` (default 6).

The host issues `GameEvent.StartGame(turnOrder)`. The reducer requires:

- the session is in `LOBBY`,
- `turnOrder.size in 2..6`,
- no duplicate UIDs.

On success the session moves to `CHOOSING_TYPE`, `activeIndex = 0`, `round = 1`,
and `currentTurn = Turn(index = 0, activeUid = turnOrder.first())`.

### 1.2 Choosing Truth or Dare

`GameEvent.ChooseType(type)` sets `currentTurn.type`:

- **TRUTH** → straight to `GENERATING` (no difficulty vote — Truths are not rated).
- **DARE** → `COLLECTING_VOTES`.

### 1.3 Difficulty voting (Dare only)

While in `COLLECTING_VOTES`, every player **other than the active player** may
cast `GameEvent.CastVote(DifficultyVote(voterUid, value))` with `value` in 1..5.
The state machine rejects a vote whose `voterUid == activeUid` ("the active
player cannot vote on their own dare"), and a vote replaces any previous vote by
the same voter (last‑vote‑per‑voter):

```kotlin
val votes = turn.votes.filterNot { it.voterUid == vote.voterUid } + vote
```

`GameEvent.CloseVoting` aggregates the votes (see §3) into a `DifficultyResult`
and advances to `GENERATING`.

### 1.4 AI challenge generation

In `GENERATING`, the app calls `RequestChallengeUseCase`, which builds a fully
personalized `ChallengeRequest` (age, language, country, interests, per‑user
limits, recent signatures for anti‑repeat) and, for Dares, passes the aggregated
`DifficultyResult` so the AI "generates an action matching exactly this
difficulty." The result is run through the moderation/limits gate (§6) with
bounded retries (`DEFAULT_MAX_ATTEMPTS = 3`). `GameEvent.ChallengeReady(challenge)`
attaches it and advances to `PERFORMING`.

### 1.5 Perform & outcome

The active player performs on live video. `GameEvent.SubmitOutcome(completed)`
maps to `RoundOutcome.COMPLETED` (true) or `RoundOutcome.FAILED` (false) and
advances to `RATING`. (`RoundOutcome` also defines `PENDING` and `SKIPPED`;
`PENDING` is the initial value, `SKIPPED` is reserved for moderation/host skips.)

### 1.6 Rating, rewards, next turn

In `RATING` the turn is final. Progression rewards are computed by
`ApplyTurnRewardsUseCase` (§4). `GameEvent.NextTurn(presentUids)` then:

1. appends the finished turn to `history`,
2. resolves who is present (empty set ⇒ everyone in `turnOrder` is present),
3. if fewer than `MIN_PLAYERS` remain present in order → `FINISHED`,
4. otherwise advances the cursor with `TurnRotation.advance` (skipping absent
   players, cyclic),
5. increments `round` by 1 **iff** the rotation wrapped past the end,
6. if `newRound > maxRounds` → `FINISHED`,
7. else starts a fresh `Turn(index = finished.index + 1, activeUid = …)` and
   returns to `CHOOSING_TYPE`.

`GameEvent.EndGame` forces `FINISHED` from any phase (host abort / room close).

---

## 2. GamePhase state machine (transition table)

`GamePhase` (`model/Game.kt`):
`LOBBY, CHOOSING_TYPE, COLLECTING_VOTES, GENERATING, PERFORMING, RATING, FINISHED`.

Each row is a legal transition. Any event not listed for a phase is **illegal**
in that phase and throws `IllegalStateException` (or `IllegalArgumentException`
for bad arguments, e.g. self‑vote, bad turn‑order size).

| From phase | Event | Guard / condition | To phase | Notes |
| --- | --- | --- | --- | --- |
| `LOBBY` | `StartGame(order)` | `order.size in 2..6`, no dups | `CHOOSING_TYPE` | `round=1`, `activeIndex=0`, first turn created |
| `CHOOSING_TYPE` | `ChooseType(TRUTH)` | — | `GENERATING` | no voting for Truth |
| `CHOOSING_TYPE` | `ChooseType(DARE)` | — | `COLLECTING_VOTES` | opens difficulty vote |
| `COLLECTING_VOTES` | `CastVote(vote)` | `vote.voterUid != activeUid` | `COLLECTING_VOTES` | stays; last‑vote‑per‑voter |
| `COLLECTING_VOTES` | `CloseVoting` | — | `GENERATING` | computes `DifficultyResult` |
| `GENERATING` | `ChallengeReady(challenge)` | — | `PERFORMING` | attaches challenge |
| `PERFORMING` | `SubmitOutcome(true)` | — | `RATING` | outcome = `COMPLETED` |
| `PERFORMING` | `SubmitOutcome(false)` | — | `RATING` | outcome = `FAILED` |
| `RATING` | `NextTurn(present)` | `presentInOrder >= 2` and `newRound <= maxRounds` | `CHOOSING_TYPE` | rotate; round++ on wrap |
| `RATING` | `NextTurn(present)` | `presentInOrder < 2` **or** `newRound > maxRounds` | `FINISHED` | game ends |
| *any* | `EndGame` | — | `FINISHED` | force end |

Notes on `NextTurn`:

- `presentUids` defaults to "all in `turnOrder`" when empty.
- `presentInOrder = turnOrder.count { it in present }`.
- `wrapped` is reported by `TurnRotation.advance`: wraps when
  `(fromIndex + step) >= order.size`. A wrap increments `round`.
- Disconnected players are simply skipped by the rotation; their turns do not
  occur until they return.

---

## 3. Difficulty aggregation rules

Implemented in `AggregateDifficultyUseCase`, producing a `DifficultyResult`
(`model/Challenge.kt`) with `average: Double`, `target: Int`, `voteCount: Int`.

Rules, in order:

1. **Active player excluded.** Any vote whose `voterUid == activePlayerUid` is
   dropped (defense in depth — the state machine already forbids it).
2. **Last‑vote‑per‑voter.** Votes are keyed by `voterUid` ("last write wins"), so
   if a voter changes their mind only their final vote counts.
3. **Neutral fallback.** If there are **no** eligible votes, the result is
   `DifficultyResult.NEUTRAL = (average = 3.0, target = 3, voteCount = 0)`.
   This covers the all‑abstained / edge cases.
4. **Average & target.** Otherwise
   `average = sum(values) / count` (exact `Double`), and
   `target = Difficulty.clamp(round(average))`, i.e. rounded to the nearest
   integer then clamped to 1..5.

The exact `average` is what the AI receives ("matching exactly this difficulty");
`target` is the rounded integer used for display, the dare XP multiplier (§4.2),
and filtering against `ContentLimits.maxDifficulty` (§6).

### 3.1 Example difficulty calculation

5‑player game; active player is `P3`. The other four vote:

| Voter | First vote | Revised vote | Counted |
| --- | --- | --- | --- |
| P1 | 4 | — | 4 |
| P2 | 2 | 3 | 3 (last wins) |
| P4 | 5 | — | 5 |
| P5 | 4 | — | 4 |
| P3 (active) | 1 | — | dropped (self) |

Eligible votes: `{P1:4, P2:3, P4:5, P5:4}`, `voteCount = 4`.

```
average = (4 + 3 + 5 + 4) / 4 = 16 / 4 = 4.0
target  = clamp(round(4.0)) = 4
DifficultyResult(average = 4.0, target = 4, voteCount = 4)
```

A non‑integer example: votes `{2, 3, 3}` →
`average = 8/3 = 2.6667`, `target = clamp(round(2.6667)) = 3`.

---

## 4. Progression: XP, levels, rewards

### 4.1 XP ↔ level curve

`LevelSystem` defines a smooth quadratic curve. The total XP required to **reach**
the start of level `L` is:

```
xpToReach(L) = 25 · (L − 1) · (L + 2)
```

Level 1 starts at 0 XP. The per‑level span grows by a constant 50 XP each level
(span to reach `L+1` from `L` = `50·L`):

| Level L | `xpToReach(L)` | XP span L → L+1 |
| --- | --- | --- |
| 1 | 0 | 100 |
| 2 | 100 | 150 |
| 3 | 250 | 200 |
| 4 | 450 | 250 |
| 5 | 700 | 300 |
| 6 | 1 000 | 350 |
| 7 | 1 350 | 400 |
| 8 | 1 750 | 450 |
| 9 | 2 200 | 500 |
| 10 | 2 700 | 550 |
| 25 | 16 200 | — |
| 50 | 63 700 | — |
| 100 | 252 450 | — |

- `levelForXp(totalXp)` inverts the curve in closed form (O(1)) with a small
  boundary correction for float drift.
- `progressFor(totalXp)` yields a `LevelProgress(level, currentLevelXp,
  xpForNextLevel, totalXp)` (drives the level bar; `fraction` is
  `currentLevelXp / xpForNextLevel`, clamped 0..1).
- **Level cap:** `LevelSystem.MAX_LEVEL = 100`. At the cap, `progressFor` reports
  `currentLevelXp = 0` and `xpForNextLevel = 0` (fraction = 1.0); XP keeps
  accumulating but the level no longer rises.
- `applyXp` never decreases XP (negative deltas are clamped to 0).

### 4.2 XP rewards per action

XP reasons and their base values (`XpReason` in `model/Progression.kt`):

| Reason | Base XP | Multiplier | Net |
| --- | --- | --- | --- |
| `TRUTH_ANSWERED` | 20 | ×1 | 20 |
| `DARE_COMPLETED` | 30 | ×1.0 … ×2.0 by difficulty | 30 … 60 |
| `DARE_FAILED` | 5 | ×1 | 5 |
| `VOTE_CAST` | 2 | ×1 | 2 |
| `GAME_FINISHED` | 15 | ×1 | 15 |
| `DAILY_LOGIN` | 10 | ×1 | 10 |
| `FRIEND_ADDED` | 5 | ×1 | 5 |

`AwardXpUseCase` returns an `XpAward(reason, amount, multiplier)` whose
`total = (amount * multiplier).toInt()`.

**Dare difficulty multiplier** (only applies to `DARE_COMPLETED`):

```
multiplier = 1.0 + (clamp(difficulty) − 1) × 0.25
```

so it is linear from **×1.0 at difficulty 1** to **×2.0 at difficulty 5**
(+0.25 per step). Concretely:

| Difficulty (target) | Multiplier | `DARE_COMPLETED` total |
| --- | --- | --- |
| 1 | 1.00 | 30 |
| 2 | 1.25 | 37 |
| 3 | 1.50 | 45 |
| 4 | 1.75 | 52 |
| 5 | 2.00 | 60 |

(Totals are truncated by `Int` conversion: e.g. `30 × 1.25 = 37.5 → 37`.)

### 4.3 Per‑turn reward aggregation

`ApplyTurnRewardsUseCase(stats, type, outcome, difficultyTarget)` is the single
place a resolved turn mutates progression. It returns
`TurnRewards(stats, award, newBadges)` and:

1. **Updates lifetime counters** (`PlayerStats`):
   - Truth + COMPLETED → `truthsAnswered += 1`
   - Dare + COMPLETED → `daresCompleted += 1`,
     `maxDifficultyCompleted = max(prev, difficultyTarget ?: 0)`
   - Dare + FAILED → `daresFailed += 1`
   - anything else (e.g. Truth not completed, SKIPPED) → no counter change
2. **Computes XP** via `AwardXpUseCase` (null when nothing is rewarded — e.g. a
   non‑completed Truth).
3. **Applies XP + recomputes level** via `LevelSystem.applyXp`.
4. **Unlocks newly earned badges** via `EvaluateBadgesUseCase`, merging them into
   `unlockedBadges`.

`votesCast`, `gamesPlayed`, and `friendsCount` are incremented by their own flows
(voting, game‑finished, social), not by this per‑turn use case.

---

## 5. Badges

`BadgeId` values (`model/Progression.kt`):
`FIRST_GAME, SOCIAL_BUTTERFLY, TRUTH_SEEKER, DAREDEVIL, FEARLESS, LEVEL_10,
LEVEL_25, LEVEL_50, GLOBETROTTER, PERFECT_VOTER, NIGHT_OWL`.

Unlock thresholds (`EvaluateBadgesUseCase.qualifiedBadges`). The use case returns
only **newly** earned badges (`qualifiedBadges − unlockedBadges`):

| Badge | Condition (on `PlayerStats`) |
| --- | --- |
| `FIRST_GAME` | `gamesPlayed >= 1` |
| `GLOBETROTTER` | `gamesPlayed >= 50` |
| `TRUTH_SEEKER` | `truthsAnswered >= 25` |
| `DAREDEVIL` | `daresCompleted >= 25` |
| `FEARLESS` | `maxDifficultyCompleted >= Difficulty.MAX` (5) |
| `SOCIAL_BUTTERFLY` | `friendsCount >= 10` |
| `PERFECT_VOTER` | `votesCast >= 100` |
| `LEVEL_10` | `level >= 10` |
| `LEVEL_25` | `level >= 25` |
| `LEVEL_50` | `level >= 50` |

`NIGHT_OWL` is **time‑of‑day based and awarded server‑side**; it is intentionally
out of scope for the client `EvaluateBadgesUseCase`.

`Badge` (the displayable record) carries `title`, `description`, and a
`Tier` (`BRONZE, SILVER, GOLD, PLATINUM`).

---

## 6. Moderation & limits gating

Defense‑in‑depth is applied to every AI‑generated challenge **before** it reaches
a player. `RequestChallengeUseCase` calls the AI, then runs each candidate through
`FilterChallengeUseCase`, retrying up to `DEFAULT_MAX_ATTEMPTS = 3` times; on each
rejection it adds the rejected challenge's `signature` to the recent list so the
generator does not re‑propose it. After `maxAttempts` it returns a typed
`DomainResult.Failure`.

`FilterChallengeUseCase(challenge, limits, age)` rejects in this order
(`limits` = the target player's `ContentLimits`; `age` from
`UserProfile.ageOn(today)`):

| # | Check | Failure |
| --- | --- | --- |
| 1 | `isAdult = (age == null || age >= 18)`; if `challenge.isAdult` and (`!limits.allowAdult` or not adult) | `DomainError.Underage` |
| 2 | Truth challenge while `!limits.allowTruth` | `DomainError.Moderation("Truth is disabled…")` |
| 3 | Dare challenge while `!limits.allowDare` | `DomainError.Moderation("Dare is disabled…")` |
| 4 | `challenge.category in limits.blockedCategories` | `DomainError.Moderation("Category is blocked…")` |
| 5 | `challenge.difficulty > limits.maxDifficulty` | `DomainError.Moderation("Above your maximum difficulty.")` |
| 6 | On‑device `ContentModerator.screen(text, allowAdult = limits.allowAdult && isAdult)` not `ALLOW` | `DomainError.Moderation(reason)` |

If all checks pass, the challenge is returned and `recordServed` is called so it
contributes to anti‑repeat history.

### 6.1 Content limits

`ContentLimits` (`model/UserProfile.kt`):

| Field | Default | Meaning |
| --- | --- | --- |
| `allowTruth` | `true` | Truth challenges permitted |
| `allowDare` | `true` | Dare challenges permitted |
| `allowAdult` | `false` | +18 content opt‑in |
| `maxDifficulty` | `Difficulty.MAX` (5) | Hard cap on dare difficulty shown |
| `blockedCategories` | `∅` | `ChallengeCategory` values to never show |

`ContentLimits.MINOR_SAFE` is applied automatically to minors:
`allowAdult = false`, `maxDifficulty = 3`, and
`blockedCategories = {ADULT, PHYSICAL}`.

`ChallengeCategory`:
`FUNNY, EMBARRASSING, DEEP, ROMANTIC, SOCIAL, PHYSICAL, CREATIVE, ADULT`.

Moderation categories screened (`ModerationCategory`):
`HATE, HARASSMENT, SEXUAL, SEXUAL_MINORS, VIOLENCE, SELF_HARM, ILLEGAL,
PERSONAL_INFO, ADULT`. Verdicts: `ALLOW, FLAG, BLOCK` (only `ALLOW` passes the
gate). Players can also file a `Report` with a `ReportReason`.

---

## 7. Video grid layouts (2–6 players)

`ComputeVideoGridLayoutUseCase(playerCount, orientation)` returns a
`VideoGridLayout(playerCount, rows, columns, orientation)`. `playerCount` must be
in 2..6. The presentation layer renders `rows × columns`, allowing the **last row
to be partially filled** (`tilesInRow(row)` handles a short final row;
`cells = rows × columns`). Layouts favor large, balanced tiles and never leave
more than one short row.

### Portrait (`Orientation.PORTRAIT`)

| Players | Rows × Cols | Arrangement (last row may be short) |
| --- | --- | --- |
| 2 | 2 × 1 | one tile stacked over another |
| 3 | 2 × 2 | top row 2, bottom row 1 |
| 4 | 2 × 2 | full 2×2 |
| 5 | 3 × 2 | rows of 2, 2, 1 |
| 6 | 3 × 2 | full 3×2 |

### Landscape (`Orientation.LANDSCAPE`)

| Players | Rows × Cols | Arrangement (last row may be short) |
| --- | --- | --- |
| 2 | 1 × 2 | side by side |
| 3 | 1 × 3 | three across |
| 4 | 2 × 2 | full 2×2 |
| 5 | 2 × 3 | rows of 3, 2 |
| 6 | 2 × 3 | full 2×3 |

Example: 5 players, portrait → `rows = 3, cols = 2`, so `tilesInRow(0) = 2`,
`tilesInRow(1) = 2`, `tilesInRow(2) = 1` (the 6th slot stays empty).

---

## 8. Worked end‑to‑end example

3 players `[A, B, C]`, `maxRounds = 10`, all present throughout.

1. `StartGame([A,B,C])` → `CHOOSING_TYPE`, round 1, active `A` (index 0).
2. `A` picks **DARE** → `COLLECTING_VOTES`. `B` votes 5, `C` votes 4.
   `CloseVoting` → `average = 4.5`, `target = clamp(round(4.5)) = 5`,
   `voteCount = 2` → `GENERATING`.
3. AI returns a difficulty‑5 dare (passes the gate) → `ChallengeReady` →
   `PERFORMING`. `A` performs; `SubmitOutcome(true)` → `RATING`.
   Rewards: `DARE_COMPLETED` × multiplier(5) = `30 × 2.0 = 60` XP;
   `daresCompleted += 1`; `maxDifficultyCompleted = max(prev, 5)`; this also
   qualifies `A` for the `FEARLESS` badge.
4. `NextTurn` → rotate to `B` (index 1, no wrap, round stays 1) → `CHOOSING_TYPE`.
5. `B` picks **TRUTH** → `GENERATING` (no vote) → … → `SubmitOutcome(true)` →
   `RATING`: `TRUTH_ANSWERED` = 20 XP, `truthsAnswered += 1`.
6. `NextTurn` → rotate to `C` (index 2) → … → `RATING`.
7. `NextTurn` after `C` wraps past the end (`index 2 → 0`), so `round = 2`,
   active `A` again → `CHOOSING_TYPE`.
8. … the cycle repeats. When a `NextTurn` would push `round` to 11
   (`> maxRounds`), the session goes to `FINISHED` instead, `currentTurn = null`,
   with every resolved turn preserved in `history`.

On `FINISHED`, each participant additionally receives `GAME_FINISHED` (+15 XP)
and `gamesPlayed += 1` via the game‑completion flow, which may unlock
`FIRST_GAME` / `GLOBETROTTER`.

---

## Economy, monetization & reputation (v2)

The **v2** enhancement adds a Gold economy, monetization, a reputation system, and
reputation‑aware matchmaking. **Every rule, reward, price and threshold is
server‑configurable via Firebase Remote Config — no app update is needed to change
them.** The full spec, with all formulas, helpers, Remote Config keys and the
server‑authority model, lives in **[`ECONOMY.md`](./ECONOMY.md)**; this is a summary.

### Gold economy (`GoldConfig`, `GoldEconomyUseCase`)

| Rule | Default | Reason |
| --- | --- | --- |
| Join / start a game (charged; also the minimum to play) | **10 Gold** | `GAME_JOIN` |
| Complete a dare / answer a truth | **+1 Gold** | `CHALLENGE_SUCCESS` |
| Refuse a question or action | **−3 Gold** | `REFUSAL` |
| Watch a rewarded video ad | **+4 Gold** | `REWARDED_VIDEO` |

Balances **never go negative** — penalties are clamped at zero
(*"Si le joueur possède moins de 3 Gold, son solde devient 0"*), and the returned
`GoldChange` reports both the new balance and the `delta` actually applied.

### Monetization (`GoldPackCalculator`)

The display reference is **40 Gold = 1 USD** (`referenceGoldPerUsd`). Gold packs are
**configured server‑side**; the defaults are `pack_40` (40 Gold / $1, flat rate) and
`pack_500` (500 Gold / $10, a **bonus** pack worth +100 Gold over the reference).
Helpers: `goldPerUsd` (value ranking), `bonusGold(pack)` (extra over reference),
`bestValuePack()` (highest `goldPerUsd` → `pack_500`). Rewarded video grants
**+4 Gold** after ad‑network SSV verification.

### Reputation (`ReputationTier`, `ReputationCalculator`)

Post‑game peer ratings (**1..5**) fold into a **running average**
(`applyRating`) and map to one of four tiers via `tierFor`:

| Tier | Condition (`ReputationConfig` defaults: `minRatings = 5`, `excellent = 4.5`, `good = 3.5`) |
| --- | --- |
| `NEW` | never rated (`ratingsCount <= 0`) |
| `FEW_RATINGS` | rated but `< 5` ratings |
| `EXCELLENT` | `>= 5` ratings and average `>= 4.5` |
| `GOOD` | `>= 5` ratings, below excellent |

**`NEW` is intentionally prioritized above `FEW_RATINGS`** so brand‑new accounts get
integrated and earn their first ratings.

### Matchmaking changes (`MatchmakingScorer`, `FormMatchUseCase`)

Same‑country is now the **top priority, then language**, with reputation weighted in
and **best‑effort gender balancing**. The weighted components sum to 1.0:

| Component | Weight |
| --- | --- |
| Country | 0.30 |
| Language | 0.22 |
| Interests | 0.18 |
| Reputation | 0.12 |
| Level | 0.08 |
| Age | 0.05 |
| Ping | 0.05 |

Reputation tier weights: `EXCELLENT 1.0 · GOOD 0.8 · NEW 0.6 · FEW_RATINGS 0.5`
(again, `NEW` above `FEW_RATINGS`). Gender (`MALE / FEMALE / UNSPECIFIED`) is a soft
signal: `FormMatchUseCase` greedily favours the under‑represented binary gender at
each step while still picking the highest‑scored candidate — *"équilibrer
hommes/femmes lorsque possible"*; `UNSPECIFIED` users never count toward balancing.

### Server authority

Balances and reputation are **mutated only by Cloud Functions** (anti‑cheat):
`economy.ts`, `reputation.ts`, `matchmaking.ts`, with parameters read from the
`config/economy` Firestore document (Remote Config's server twin). Every Gold change
is recorded in an **append‑only ledger** under `users/{uid}/transactions`
(`delta`, `reason`, `balanceAfter`, …). See [`ECONOMY.md`](./ECONOMY.md) for the full
Remote Config key list and ledger schema.
