# Economy, Monetization & Reputation — Action ou Vérité Live

This document is the authoritative spec for the **v2** systems added on top of the
core game loop (see [`GAME_DESIGN.md`](./GAME_DESIGN.md)): a **Gold economy**, a
**monetization** layer (rewarded video + purchasable Gold packs), a **reputation**
system, and the **reputation-aware matchmaking** changes.

Like the rest of the domain, every rule lives in pure, unit-testable Kotlin and is
mirrored field-for-field on the backend. **Everything is server-tunable via Firebase
Remote Config — no app update is required to change a price, reward, threshold or
pack.** The compiled defaults below are the fallback values when a Remote Config key
is unset (so the app always has a sane config offline).

| Concern | Source of truth |
| --- | --- |
| Gold parameters & packs | `domain/.../model/Economy.kt` (`GoldConfig`, `GoldPack`, `GoldReason`, `GoldChange`) |
| Gold rules (pure) | `domain/.../usecase/economy/GoldEconomyUseCase.kt` |
| Store math / pack ranking | `domain/.../usecase/economy/GoldPackCalculator.kt` |
| Reputation model | `domain/.../model/Reputation.kt` (`ReputationTier`, `Reputation`, `PlayerRating`, `ReputationConfig`) |
| Reputation math (pure) | `domain/.../usecase/reputation/ReputationCalculator.kt` |
| Gender model | `domain/.../model/Gender.kt` |
| Matchmaking scoring | `domain/.../usecase/matchmaking/MatchmakingScorer.kt` |
| Group formation / gender balancing | `domain/.../usecase/matchmaking/FormMatchUseCase.kt` |
| Aggregate config | `domain/.../model/AppConfig.kt` |
| Remote Config mapping & keys | `data/.../repository/RemoteConfigRepositoryImpl.kt` |
| Server-authoritative wallet | `data/.../repository/EconomyRepositoryImpl.kt`, `functions/src/economy.ts` |
| Server-authoritative ratings | `data/.../repository/ReputationRepositoryImpl.kt`, `functions/src/reputation.ts` |
| Server matchmaking | `functions/src/matchmaking.ts` |
| Shared backend contracts/defaults | `functions/src/types.ts` |

---

## 1. Gold economy

The Gold economy is a soft currency that gates and rewards play. Its parameters are
the `GoldConfig` data class (`model/Economy.kt`); the pure rules are
`GoldEconomyUseCase` (`usecase/economy/GoldEconomyUseCase.kt`).

### 1.1 Rules & defaults

| Rule | `GoldConfig` field | Default | Reason (`GoldReason`) | Use-case method |
| --- | --- | --- | --- | --- |
| Join / start a game (charged **and** the minimum required to play) | `joinCost` | **10** | `GAME_JOIN` | `chargeForGame`, `canJoin` |
| Complete a dare / answer a truth | `rewardSuccess` | **+1** | `CHALLENGE_SUCCESS` | `rewardSuccess` |
| Refuse a question or action | `penaltyRefuse` | **−3** | `REFUSAL` | `penalizeRefusal` |
| Watch a rewarded video ad | `rewardedVideoGold` | **+4** | `REWARDED_VIDEO` | `grantRewardedVideo` |
| Purchase a Gold pack | (per `GoldPack.gold`) | — | `PURCHASE` | `applyPurchase` |

- **`canJoin(balance, config)`** returns `balance >= config.joinCost` — a player
  needs at least **10 Gold** to enter a game, and starting one debits that 10.
- **Balances never go negative.** Every penalty/charge is clamped at zero. The −3
  refusal penalty is the canonical case from the spec:

  > **"Si le joueur possède moins de 3 Gold, son solde devient 0."**

  i.e. a player with 2 Gold who refuses ends at **0**, not −1.

### 1.2 Clamping & the reported delta

`GoldEconomyUseCase` computes the *intended* change and returns a `GoldChange`
holding both the resulting **`newBalance`** (never negative) and the **`delta`
actually applied** — which may be smaller in magnitude than the nominal amount when
a penalty is clamped:

```kotlin
private fun debit(balance: Long, amount: Long, reason: GoldReason): GoldChange {
    val safeBalance = balance.coerceAtLeast(0)
    val newBalance = (safeBalance - amount.coerceAtLeast(0)).coerceAtLeast(0)
    return GoldChange(newBalance = newBalance, delta = newBalance - safeBalance, reason = reason)
}
```

| Starting balance | Action | Nominal | `newBalance` | Reported `delta` |
| --- | --- | --- | --- | --- |
| 12 | refuse | −3 | 9 | −3 |
| 2 | refuse | −3 | **0** | **−2** (clamped) |
| 0 | refuse | −3 | 0 | 0 |
| 5 | success | +1 | 6 | +1 |

> This is the *intended* change only. The **authoritative** mutation always happens
> server-side (see §6); the client computation drives optimistic UI and tests.

---

## 2. Monetization

Players top up Gold two ways — both credited only by Cloud Functions.

### 2.1 Rewarded video

Watching a rewarded ad grants `rewardedVideoGold` (**default +4 Gold**,
`GoldReason.REWARDED_VIDEO`). Server-side (`functions/src/economy.ts`
`grantRewardedVideo`) the ad's **Server-Side Verification (SSV) token** must be
validated with the ad network before the grant is applied (a documented integration
point); an empty token throws `MISSING_SSV_TOKEN`.

### 2.2 Gold packs & the USD reference rate

`GoldConfig.referenceGoldPerUsd` (**default 40**) is the display reference:
**40 Gold = 1 USD**. Packs are `GoldPack(id, gold, priceUsdCents)` and are
**configured server-side** so the catalogue can be reshaped without an app release.

The default catalogue (`GoldConfig.DEFAULT_PACKS`):

| Pack id | Gold | Price | `goldPerUsd` | Bonus over reference |
| --- | --- | --- | --- | --- |
| `pack_40` | 40 | $1.00 (`priceUsdCents = 100`) | 40.0 | 0 (flat reference rate) |
| `pack_500` | 500 | $10.00 (`priceUsdCents = 1000`) | 50.0 | **+100 Gold** (bonus pack) |

So **500 Gold = 10 USD** is the bonus tier: at the flat 40/USD reference, $10 would
buy 400 Gold, so the pack throws in **100 extra Gold**.

### 2.3 Store helpers (`GoldPackCalculator`)

`GoldPackCalculator` (`usecase/economy/GoldPackCalculator.kt`) provides pure,
config-driven math for the store UI:

| Helper | Purpose | On the defaults |
| --- | --- | --- |
| `goldToUsdCents(gold, config)` | Reference USD-cents value of a Gold amount | `goldToUsdCents(40) = 100` ($1) |
| `usdCentsToGold(usdCents, config)` | Reference Gold value of a USD-cents amount | `usdCentsToGold(1000) = 400` |
| `GoldPack.goldPerUsd` | Gold per USD — higher is better value | `pack_40 → 40.0`, `pack_500 → 50.0` |
| `bonusGold(pack, config)` | Extra Gold a pack gives over the flat reference rate (clamped ≥ 0) | `bonusGold(pack_500) = 500 − 400 = 100` |
| `bestValuePack(config)` | The pack with the highest `goldPerUsd` (or `null`) | `pack_500` |

The reference rate is coerced to ≥ 1 to avoid division by zero, and conversions are
rounded with `roundToLong()`.

---

## 3. Reputation

Reputation is derived from **post-game peer ratings** (each player rates the others
**1..5**) and is used to prioritize matchmaking. The model lives in
`model/Reputation.kt`; the pure math is `ReputationCalculator`
(`usecase/reputation/ReputationCalculator.kt`).

### 3.1 The four tiers

`enum class ReputationTier { EXCELLENT, GOOD, NEW, FEW_RATINGS }` — declared
**best-to-integrate first**. Crucially, **`NEW` is intentionally ranked above
`FEW_RATINGS`** so brand-new accounts get matched and earn their first ratings
("doivent être régulièrement intégrés").

| Tier | Meaning | Condition (`tierFor`) |
| --- | --- | --- |
| `EXCELLENT` | Enough ratings, high average | `ratingsCount >= minRatingsForTier` **and** `averageScore >= excellentThreshold` |
| `GOOD` | Enough ratings, below excellent | `ratingsCount >= minRatingsForTier` **and** `averageScore < excellentThreshold` |
| `NEW` | Never rated (prioritized for integration) | `ratingsCount <= 0` |
| `FEW_RATINGS` | Rated but below the confidence threshold | `0 < ratingsCount < minRatingsForTier` |

### 3.2 Thresholds (`ReputationConfig`, server-configurable)

| Field | Default | Meaning |
| --- | --- | --- |
| `minRatingsForTier` | **5** | Below this, a rated account is `FEW_RATINGS` (confidence floor) |
| `excellentThreshold` | **4.5** | Average at/above this (with enough ratings) ⇒ `EXCELLENT` |
| `goodThreshold` | **3.5** | The `GOOD` band threshold |

### 3.3 `tierFor` and `applyRating` (running average)

`tierFor(averageScore, ratingsCount, config)` classifies, in order: `NEW` (unrated)
→ `FEW_RATINGS` (under the confidence floor) → `EXCELLENT` (high average) → else
`GOOD`.

`applyRating(current, score, config)` folds a new 1..5 score into a **running
average** and recomputes the tier:

```kotlin
val newCount = current.ratingsCount + 1
val newAverage = (current.averageScore * current.ratingsCount + score) / newCount
```

`withTier(reputation, config)` re-derives the tier for an existing `Reputation`
(e.g. after a config change). A fresh account is `Reputation.NEW_ACCOUNT`
(`averageScore = 0.0`, `ratingsCount = 0`, `tier = NEW`).

**Worked example** — a user with `averageScore = 4.6`, `ratingsCount = 5` receives a
new rating of `3`:

```
newCount   = 6
newAverage = (4.6 × 5 + 3) / 6 = 26 / 6 = 4.333…
tier       = GOOD   (count 6 ≥ 5, but 4.333 < 4.5)
```

---

## 4. Matchmaking changes (reputation-aware, country-first)

Matchmaking now factors in **reputation** and **gender balancing**, and reorders the
priority so **same country is the top priority, then same language**. Pairwise
scoring is `MatchmakingScorer` (`usecase/matchmaking/MatchmakingScorer.kt`); group
formation and gender balancing are `FormMatchUseCase`
(`usecase/matchmaking/FormMatchUseCase.kt`).

### 4.1 Weighted compatibility score (0.0..1.0)

The weighted components sum to **1.0**:

| Component | Weight (constant) | How it is scored |
| --- | --- | --- |
| **Country** (same-country-first) | **0.30** (`W_COUNTRY`) | 1.0 if same `countryCode`, else 0.0 |
| **Language** (then language) | **0.22** (`W_LANGUAGE`) | 1.0 if same `languageCode`, else 0.0 |
| **Interests** | **0.18** (`W_INTERESTS`) | Jaccard overlap of interest sets |
| **Reputation** | **0.12** (`W_REPUTATION`) | tier weight (see §4.2) |
| **Level** | **0.08** (`W_LEVEL`) | linear proximity, decays to 0 at `maxLevelGap` (10) |
| **Age** | **0.05** (`W_AGE`) | proximity to `maxAgeGap` (8); neutral **0.5** if either age unknown |
| **Ping** | **0.05** (`W_PING`) | `1 − min(1, pingMs / maxPingMs)` (`maxPingMs` = 300) |

The final score is `coerceIn(0.0, 1.0)`. The formula is **identical on the backend**
(`functions/src/matchmaking.ts`, same `W_*` constants) so on-device and server
ranking agree.

### 4.2 Reputation weighting

The candidate's tier maps to a preference weight. **`NEW` is boosted above
`FEW_RATINGS`** so new accounts are regularly integrated to earn their first
ratings:

| Tier | Weight (`reputationValue`) |
| --- | --- |
| `EXCELLENT` | 1.0 |
| `GOOD` | 0.8 |
| `NEW` | **0.6** |
| `FEW_RATINGS` | 0.5 |

### 4.3 Gender balancing (best-effort)

`Gender { MALE, FEMALE, UNSPECIFIED }` (`model/Gender.kt`) is a **soft signal only**
used to balance rooms ("équilibrer hommes/femmes lorsque possible"). `UNSPECIFIED`
users are never penalized and don't count toward balancing.

When `MatchPreferences.balanceGender` is on (default `true`),
`FormMatchUseCase.selectGenderBalanced` does a **greedy balanced pick** from the
score-sorted candidates: at each step, if one binary gender is under-represented in
the group so far, it takes the **highest-scored remaining candidate of that gender**
(falling back to the overall best when none remain). It always picks within the
score order, so balancing never overrides compatibility wholesale — it is
**best-effort**. The same greedy logic is mirrored on the backend
(`matchmaking.ts` `selectGenderBalanced`).

Hard filters (`passesHardFilters`) still apply first: `sameLanguageOnly`,
`sameCountryOnly`, `adultOnly`, `maxLevelGap`, `maxPingMs`, `maxAgeGap`. Group size
is bounded by `Room.MIN_PLAYERS = 2 .. Room.MAX_PLAYERS = 6`; if not enough
compatible candidates exist to reach the minimum, the result is `null`.

---

## 5. Remote Config — everything is server-tunable

`AppConfig` (`model/AppConfig.kt`) aggregates `GoldConfig` + `ReputationConfig` and
is loaded from **Firebase Remote Config** by `RemoteConfigRepositoryImpl`
(`data/.../repository/RemoteConfigRepositoryImpl.kt`). Scalars come from typed keys;
the Gold packs are a JSON array string so the catalogue can be reshaped server-side.
**Unset keys fall back to the in-code defaults**, so the app always has a sane config
offline. This realizes the spec requirement:

> "Toutes les règles, récompenses, prix et paramètres doivent être configurables
> depuis le serveur" — i.e. **no app update is needed** to change any rule.

### 5.1 Remote Config keys

| Remote Config key | Maps to | Default |
| --- | --- | --- |
| `gold_join_cost` | `GoldConfig.joinCost` | 10 |
| `gold_reward_success` | `GoldConfig.rewardSuccess` | 1 |
| `gold_penalty_refuse` | `GoldConfig.penaltyRefuse` | 3 |
| `gold_rewarded_video` | `GoldConfig.rewardedVideoGold` | 4 |
| `gold_reference_per_usd` | `GoldConfig.referenceGoldPerUsd` | 40 |
| `gold_packs` | `GoldConfig.packs` (JSON array) | `pack_40`, `pack_500` |
| `rep_min_ratings` | `ReputationConfig.minRatingsForTier` | 5 |
| `rep_excellent_threshold` | `ReputationConfig.excellentThreshold` | 4.5 |
| `rep_good_threshold` | `ReputationConfig.goodThreshold` | 3.5 |

`gold_packs` is a JSON array of `{ "id", "gold", "priceUsdCents" }` objects, e.g.:

```json
[
  { "id": "pack_40",  "gold": 40,  "priceUsdCents": 100  },
  { "id": "pack_500", "gold": 500, "priceUsdCents": 1000 }
]
```

The repository seeds these defaults via `setDefaultsAsync`, exposes the config as a
`StateFlow<AppConfig>` (`observeConfig`), and `refresh()` calls
`fetchAndActivate()`. A `0`/`0.0` value is treated as "unset" and falls back to the
compiled default.

### 5.2 The `config/economy` Firestore document (server twin)

The Cloud Functions read economy values from the **`config/economy` Firestore
document** — Remote Config's server-side twin — falling back to
`ECONOMY_DEFAULTS`/`REPUTATION_DEFAULTS` in `functions/src/types.ts`. This keeps the
backend and Remote Config in lockstep:

- `config/economy.joinCost / rewardSuccess / penaltyRefuse / rewardedVideoGold`
  drive the wallet mutations (`economy.ts` `loadConfig`).
- `config/economy.packs` is the authoritative pack → Gold map used to validate
  purchases (`economy.ts` `purchasePack`; an unknown pack throws `UNKNOWN_PACK`).

`functions/src/types.ts` defaults mirror the domain exactly:

```ts
export const ECONOMY_DEFAULTS = { joinCost: 10, rewardSuccess: 1, penaltyRefuse: 3, rewardedVideoGold: 4 };
export const REPUTATION_DEFAULTS = { minRatingsForTier: 5, excellentThreshold: 4.5, goodThreshold: 3.5 };
```

---

## 6. Server authority & the transactions ledger (anti-cheat)

**Clients never write their own balance or reputation.** Every mutation is performed
by a Cloud Function, so the values are server-authoritative (anti-cheat).

### 6.1 Wallet

`EconomyRepositoryImpl` (`data/.../repository/EconomyRepositoryImpl.kt`) **reads** the
balance from the user document's `gold` field (`observeBalance`) but routes **every
change** through callable Cloud Functions:

| Repository call | Callable (`functions/src/index.ts`) | Backend (`economy.ts`) |
| --- | --- | --- |
| `chargeForGame(roomId)` | `chargeGameFee` | `chargeGameFee` — fails `INSUFFICIENT_FUNDS` if broke |
| `rewardChallengeSuccess(roomId)` | `rewardChallenge` | `rewardChallenge` |
| `penalizeRefusal(roomId)` | `penalizeRefusal` | `penalizeRefusal` |
| `grantRewardedVideo(adSsvToken)` | `grantRewardedVideo` | `grantRewardedVideo` — requires SSV token |
| `purchasePack(packId, purchaseToken)` | `purchasePack` | `purchasePack` — verifies Play purchase |

`economy.ts` `applyDelta` runs inside a **Firestore transaction** against
`users/{uid}.gold`, clamps the result at zero, and writes an **append-only ledger
entry**:

```ts
const next = Math.max(0, current + delta);   // clamped at zero
const applied = next - current;
tx.set(userRef, { gold: next }, { merge: true });
tx.set(userRef.collection(SUBCOLLECTION_TRANSACTIONS).doc(randomUUID()), {
  delta: applied, reason, balanceAfter: next, createdAtEpochMs: Date.now(), ...meta,
});
```

For the game entry fee, `chargeGameFee` passes `requireFunds = true`, so an
underfunded join throws `INSUFFICIENT_FUNDS` rather than silently clamping.

#### Transactions ledger

Each change appends a document under **`users/{uid}/transactions`**
(`SUBCOLLECTION_TRANSACTIONS`) for auditing:

| Field | Meaning |
| --- | --- |
| `delta` | Gold actually applied (post-clamp; can be smaller than nominal) |
| `reason` | `GAME_JOIN` / `CHALLENGE_SUCCESS` / `REFUSAL` / `REWARDED_VIDEO` / `PURCHASE` / `ADMIN_GRANT` |
| `balanceAfter` | Resulting balance |
| `createdAtEpochMs` | Server timestamp |
| `…meta` | Context, e.g. `roomId`, `packId`, `ssv` |

### 6.2 Reputation

`ReputationRepositoryImpl` reads the nested `reputation` map from the user doc but
submits ratings via the `submitRatings` callable. `functions/src/reputation.ts`
folds each 1..5 score into the target's **running average** inside a transaction,
recomputes the tier with `reputationTierFor` (the exact mirror of
`ReputationCalculator`), and **de-dupes each `(rater, target, room)` tuple** (stored
under `rooms/{roomId}/ratings`, `SUBCOLLECTION_RATINGS`) so a player can only rate a
given target once per room. Self-ratings are dropped.
