# Action ou Vérité Live — Cloud Functions

Firebase Cloud Functions (TypeScript, Gen 2) backing the Android app. The app
only ever talks to these authenticated, App-Check-enforced callable endpoints —
the AI key and the heavy logic stay server-side.

## Functions

| Name (callable)     | Purpose                                                        |
| ------------------- | ------------------------------------------------------------- |
| `generateChallenge` | Generates a unique, personalized Truth/Dare challenge via the Anthropic Claude API. |
| `moderateText`      | Classifies text for safety (Claude classifier with a rule-based fallback). |
| `requestMatch`      | Server-authoritative matchmaking over the `matchQueue` collection. |

All three require `request.auth` (UNAUTHENTICATED otherwise) and enforce App
Check (`enforceAppCheck: true`). Their exact request/response shapes mirror the
client in `data/.../remote/FunctionsService.kt`.

## AI ("IA")

`generateChallenge` uses the official `@anthropic-ai/sdk`. It calls
`client.messages.create({ model, max_tokens, system, messages, tools, tool_choice })`
with a single `challenge` tool whose `input_schema` defines the structured JSON
output, and reads the resulting `tool_use` block's `input`. The system prompt
encodes the spec's IA requirements: respond in the player's `languageCode`,
adapt to age/country/interests/limits, match `difficulty.target` exactly on the
1..5 scale for DARE, never repeat any `recentSignatures`, never produce adult
content for minors, and keep everything safe, legal, and doable on a video call.

- **Model:** `claude-haiku-4-5` (`MODEL` constant in `src/challenge.ts`) — fast
  and cheap for high per-turn volume. Swap to `claude-opus-4-8` for maximum
  quality at higher cost/latency.
- **Signature:** `sha1` of the normalized (trim + lowercase + collapsed
  whitespace) challenge text, so history/anti-repeat logic is stable.
- **`isAdult`** is forced to `false` for minors or players without the adult
  opt-in, regardless of the model's claim (defense in depth).

`moderateText` uses the same SDK/model via a `moderation` tool, and falls back to
a deterministic rule-based classifier when no key is configured or the model call
fails — so moderation never silently disappears. Verdicts map to the domain
`ModerationCategory` enum; `SEXUAL_MINORS`, `HATE`, `ILLEGAL`, and `SELF_HARM`
are **always** BLOCK.

## Matchmaking

`requestMatch` reads up to 50 `matchQueue` docs, scores each candidate against
the seeker with the **same weights** as the on-device `MatchmakingScorer`
(language .30, interests .25, level .15, age .10, country .10, ping .10), greedily
forms a group of up to `desiredSize`, writes a `RoomDto`-shaped doc to `rooms`
inside a transaction (removing matched players from the queue), and returns
`{ roomId }` — or `{ roomId: null }` while still searching.

**Simplifications (intentional):** greedy top-N selection rather than global
optimization; concurrency handled with a transaction over the chosen queue docs
(a player claimed by a parallel match is dropped, possibly shrinking the group);
`pingMs` is taken from the client-written queue doc.

## Configuration

The Anthropic key is read from `process.env.ANTHROPIC_API_KEY`, wired through
Firebase Secret Manager via `defineSecret("ANTHROPIC_API_KEY")`:

```bash
firebase functions:secrets:set ANTHROPIC_API_KEY
```

The model uses `client.messages.create` from `@anthropic-ai/sdk`; the SDK reads
the key from the `apiKey` passed at construction (sourced from the secret).

## Develop / deploy

```bash
cd functions
npm install
npm run build          # tsc -> lib/
npm run lint           # eslint (optional)
npm run serve          # build + functions emulator
npm run deploy         # build + firebase deploy --only functions
```

Requires Node 20 (see `engines` in `package.json`). The Firebase project's
`firebase.json` should point `functions.source` at this directory.
