/**
 * Cloud Functions entrypoint for "Action ou Vérité Live".
 *
 * Exposes three authenticated, App-Check-enforced callable functions consumed by
 * the Android app via `FunctionsService` (data layer):
 *   - generateChallenge — AI Truth/Dare generation (Anthropic Claude)
 *   - moderateText      — content safety classifier
 *   - requestMatch      — server-authoritative matchmaking
 *
 * The Anthropic API key lives in Secret Manager (defineSecret), never in code.
 */

import { initializeApp } from "firebase-admin/app";
import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";

import { generateChallenge as runGenerateChallenge } from "./challenge";
import { moderateText as runModerateText } from "./moderation";
import { requestMatch as runRequestMatch } from "./matchmaking";
import {
  ChallengeOutput,
  ContentLimits,
  DifficultyTarget,
  GenerateChallengeInput,
  ModerationOutput,
  RequestMatchInput,
  RequestMatchOutput,
} from "./types";

initializeApp();

/** Anthropic Claude API key — set via `firebase functions:secrets:set ANTHROPIC_API_KEY`. */
const anthropicApiKey = defineSecret("ANTHROPIC_API_KEY");

/** Shared callable options: App Check enforced, generous timeout for AI calls. */
const baseOptions = {
  enforceAppCheck: true,
  timeoutSeconds: 60,
  memory: "256MiB" as const,
};

/** Throws UNAUTHENTICATED unless the request carries a verified auth token. */
function requireAuth(request: CallableRequest): string {
  const uid = request.auth?.uid;
  if (uid === undefined || uid.length === 0) {
    throw new HttpsError("unauthenticated", "Sign-in is required.");
  }
  return uid;
}

/** Coerces the untyped callable payload into a typed `GenerateChallengeInput`. */
function parseChallengeInput(data: any): GenerateChallengeInput {
  if (data?.type !== "TRUTH" && data?.type !== "DARE") {
    throw new HttpsError("invalid-argument", "type must be TRUTH or DARE.");
  }
  const limitsRaw = data.limits ?? {};
  const limits: ContentLimits = {
    allowAdult: limitsRaw.allowAdult === true,
    maxDifficulty:
      typeof limitsRaw.maxDifficulty === "number" ? limitsRaw.maxDifficulty : 5,
    blockedCategories: Array.isArray(limitsRaw.blockedCategories)
      ? limitsRaw.blockedCategories.filter((c: unknown) => typeof c === "string")
      : [],
  };
  let difficulty: DifficultyTarget | null = null;
  if (data.difficulty != null && typeof data.difficulty === "object") {
    difficulty = {
      average:
        typeof data.difficulty.average === "number"
          ? data.difficulty.average
          : 3,
      target:
        typeof data.difficulty.target === "number"
          ? data.difficulty.target
          : 3,
    };
  }
  return {
    type: data.type,
    targetUid: typeof data.targetUid === "string" ? data.targetUid : "",
    languageCode: typeof data.languageCode === "string" ? data.languageCode : "en",
    countryCode: typeof data.countryCode === "string" ? data.countryCode : "US",
    age: typeof data.age === "number" ? data.age : null,
    interests: Array.isArray(data.interests)
      ? data.interests.filter((i: unknown) => typeof i === "string")
      : [],
    limits,
    difficulty,
    recentSignatures: Array.isArray(data.recentSignatures)
      ? data.recentSignatures.filter((s: unknown) => typeof s === "string")
      : [],
  };
}

/**
 * generateChallenge — produces one personalized Truth/Dare challenge via Claude.
 */
export const generateChallenge = onCall(
  { ...baseOptions, secrets: [anthropicApiKey] },
  async (request): Promise<ChallengeOutput> => {
    requireAuth(request);
    const input = parseChallengeInput(request.data);
    const key = anthropicApiKey.value();
    if (key.length === 0) {
      throw new HttpsError(
        "failed-precondition",
        "ANTHROPIC_API_KEY secret is not configured.",
      );
    }
    try {
      return await runGenerateChallenge(input, key);
    } catch (err) {
      console.error("generateChallenge failed", err);
      throw new HttpsError("internal", "Challenge generation failed.");
    }
  },
);

/**
 * moderateText — classifies text for safety (Claude classifier + rule fallback).
 */
export const moderateText = onCall(
  { ...baseOptions, secrets: [anthropicApiKey] },
  async (request): Promise<ModerationOutput> => {
    requireAuth(request);
    const text =
      typeof request.data?.text === "string" ? request.data.text : "";
    if (text.length === 0) {
      throw new HttpsError("invalid-argument", "text is required.");
    }
    // moderation.ts handles a missing key by using the rule-based fallback.
    const key = anthropicApiKey.value();
    return runModerateText(text, key.length > 0 ? key : undefined);
  },
);

/**
 * requestMatch — places/refreshes the caller in the queue and tries to form a
 * room. Returns `{ roomId }` (null while still searching).
 */
export const requestMatch = onCall(
  baseOptions,
  async (request): Promise<RequestMatchOutput> => {
    const uid = requireAuth(request);
    const data = (request.data ?? {}) as RequestMatchInput;
    const prefs: RequestMatchInput = {
      desiredSize:
        typeof data.desiredSize === "number" ? data.desiredSize : undefined,
      sameLanguageOnly: data.sameLanguageOnly,
      sameCountryOnly: data.sameCountryOnly,
      adultOnly: data.adultOnly,
      languageCode: data.languageCode,
      countryCode: data.countryCode,
    };
    try {
      const roomId = await runRequestMatch(uid, prefs);
      return { roomId };
    } catch (err) {
      console.error("requestMatch failed", err);
      throw new HttpsError("internal", "Matchmaking failed.");
    }
  },
);
