/**
 * Shared request/response contracts for the callable functions.
 *
 * These mirror — field-for-field — the maps the Android client builds in
 * `data/.../remote/FunctionsService.kt` and the domain enums in
 * `domain/.../model/`. Keep the string literal unions in sync with the Kotlin
 * `enum class` definitions; the client sends `Enum.name` (UPPER_SNAKE) on the
 * wire, so the casing here must match exactly.
 */

/** Mirrors `ChallengeType` (Challenge.kt). */
export type ChallengeType = "TRUTH" | "DARE";

/** Mirrors `ChallengeCategory` (UserProfile.kt). */
export type ChallengeCategory =
  | "FUNNY"
  | "EMBARRASSING"
  | "DEEP"
  | "ROMANTIC"
  | "SOCIAL"
  | "PHYSICAL"
  | "CREATIVE"
  | "ADULT";

export const CHALLENGE_CATEGORIES: readonly ChallengeCategory[] = [
  "FUNNY",
  "EMBARRASSING",
  "DEEP",
  "ROMANTIC",
  "SOCIAL",
  "PHYSICAL",
  "CREATIVE",
  "ADULT",
];

/** Mirrors `Interest` (UserProfile.kt). */
export type Interest =
  | "MUSIC"
  | "SPORTS"
  | "GAMING"
  | "MOVIES"
  | "TRAVEL"
  | "FOOD"
  | "ART"
  | "TECH"
  | "FASHION"
  | "FITNESS"
  | "BOOKS"
  | "SCIENCE"
  | "NATURE"
  | "PARTY"
  | "ROMANCE"
  | "COMEDY";

/** Mirrors `ModerationVerdict` (Moderation.kt). */
export type ModerationVerdict = "ALLOW" | "FLAG" | "BLOCK";

/** Mirrors `ModerationCategory` (Moderation.kt). */
export type ModerationCategory =
  | "HATE"
  | "HARASSMENT"
  | "SEXUAL"
  | "SEXUAL_MINORS"
  | "VIOLENCE"
  | "SELF_HARM"
  | "ILLEGAL"
  | "PERSONAL_INFO"
  | "ADULT";

/** Mirrors `ChallengeRequest.limits` -> `ContentLimits` (UserProfile.kt). */
export interface ContentLimits {
  allowAdult: boolean;
  maxDifficulty: number; // 1..5
  blockedCategories: ChallengeCategory[];
}

/** Mirrors `DifficultyResult` (Challenge.kt). */
export interface DifficultyTarget {
  average: number;
  target: number; // clamped 1..5
}

/** Exact payload built by `FunctionsService.generateChallenge`. */
export interface GenerateChallengeInput {
  type: ChallengeType;
  targetUid: string;
  languageCode: string;
  countryCode: string;
  age: number | null;
  interests: Interest[];
  limits: ContentLimits;
  difficulty: DifficultyTarget | null; // null for TRUTH
  recentSignatures: string[];
}

/** Maps onto `ChallengeDto` consumed by `FunctionsService.generateChallenge`. */
export interface ChallengeOutput {
  id: string;
  type: ChallengeType;
  text: string;
  category: ChallengeCategory;
  difficulty: number; // 1..5
  languageCode: string;
  isAdult: boolean;
  signature: string;
  generatedAtEpochMs: number;
}

/** Input/output for `FunctionsService.moderateText`. */
export interface ModerateTextInput {
  text: string;
}

export interface ModerationOutput {
  verdict: ModerationVerdict;
  categories: ModerationCategory[];
  score: number; // 0..1
  reason?: string;
}

/**
 * Matchmaking preferences as sent by `MatchmakingRepositoryImpl.toMap`. The
 * seeker's own language/country are folded in so the backend can score without a
 * separate profile read.
 */
export interface RequestMatchInput {
  desiredSize?: number;
  sameLanguageOnly?: boolean;
  sameCountryOnly?: boolean;
  adultOnly?: boolean;
  languageCode?: string;
  countryCode?: string;
}

export interface RequestMatchOutput {
  roomId: string | null;
}

/** Capacity bounds — mirrors `Room.MIN_PLAYERS`/`MAX_PLAYERS`. */
export const ROOM_MIN_PLAYERS = 2;
export const ROOM_MAX_PLAYERS = 6;

/** Difficulty bounds — mirrors `Difficulty.MIN`/`MAX`. */
export const DIFFICULTY_MIN = 1;
export const DIFFICULTY_MAX = 5;

/** Firestore collection names — mirrors `FirestorePaths`. */
export const COLLECTION_MATCH_QUEUE = "matchQueue";
export const COLLECTION_ROOMS = "rooms";
