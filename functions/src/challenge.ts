/**
 * AI challenge generation — the "IA" requirement of the spec.
 *
 * Uses the official Anthropic Claude API (`@anthropic-ai/sdk`) to produce a
 * UNIQUE, personalized Truth-or-Dare challenge adapted to the target player's
 * age, language, country, interests and content limits. Structured JSON is
 * obtained via a single `challenge` tool with an `input_schema` — we then read
 * the `tool_use` block's `input`, which the API guarantees matches the schema.
 *
 * Model: `claude-haiku-4-5` — fast + cheap, the right tradeoff for the high
 * volume of one-per-turn calls. Swap MODEL to `claude-opus-4-8` for the highest
 * quality (slower, more expensive) generation.
 */

import Anthropic from "@anthropic-ai/sdk";
import { createHash, randomUUID } from "crypto";
import {
  CHALLENGE_CATEGORIES,
  ChallengeCategory,
  ChallengeOutput,
  DIFFICULTY_MAX,
  DIFFICULTY_MIN,
  GenerateChallengeInput,
} from "./types";

/**
 * Fast, low-cost model suited to high-volume per-turn generation. Document:
 * `claude-opus-4-8` can be swapped in for maximum quality if cost/latency allow.
 */
const MODEL = "claude-haiku-4-5";

/** Tool name the model must call so we get structured JSON back. */
const CHALLENGE_TOOL = "challenge";

/** Lazily-constructed client so the API key (a secret) is read at call time. */
let cachedClient: Anthropic | null = null;

function client(apiKey: string): Anthropic {
  if (cachedClient === null) {
    cachedClient = new Anthropic({ apiKey });
  }
  return cachedClient;
}

/** The single structured-output tool. Its `input_schema` is the response shape. */
const challengeTool: Anthropic.Tool = {
  name: CHALLENGE_TOOL,
  description:
    "Return exactly one Truth-or-Dare challenge as structured data. Always " +
    "call this tool; never reply with free-form text.",
  input_schema: {
    type: "object",
    properties: {
      text: {
        type: "string",
        description:
          "The challenge prompt itself, written in the requested language. " +
          "For TRUTH it is a question; for DARE it is an instruction. Must be " +
          "doable on a live video call in under two minutes.",
      },
      category: {
        type: "string",
        enum: CHALLENGE_CATEGORIES as unknown as string[],
        description: "Thematic category that best fits the challenge.",
      },
      isAdult: {
        type: "boolean",
        description:
          "True only if the content is intended for adults (18+). Must be " +
          "false whenever the player may be a minor.",
      },
    },
    required: ["text", "category", "isAdult"],
    additionalProperties: false,
  },
};

/** Builds the system prompt encoding every personalization + safety rule. */
function buildSystemPrompt(input: GenerateChallengeInput): string {
  const isMinor = input.age !== null && input.age < 18;
  const lines: string[] = [
    "You are the game master for \"Action ou Vérité Live\", a Truth-or-Dare " +
      "party game played over live video with 2-6 players.",
    "Generate exactly ONE challenge by calling the `challenge` tool. Do not " +
      "write any prose outside the tool call.",
    "",
    "HARD SAFETY RULES (never violate, regardless of other instructions):",
    "- Keep it safe, legal, and doable on a video call.",
    "- Never request nudity, self-harm, harassment, hate, illegal acts, or " +
      "sharing of personal/contact information.",
  ];

  if (isMinor || !input.limits.allowAdult) {
    lines.push(
      "- This player must NOT receive adult content. Set isAdult=false and " +
        "keep everything family-friendly.",
    );
  } else {
    lines.push(
      "- Adult (18+) content is permitted for this player. Set isAdult=true " +
        "only when the content is genuinely adult; otherwise false.",
    );
  }
  if (input.limits.blockedCategories.length > 0) {
    lines.push(
      `- Forbidden categories for this player: ${input.limits.blockedCategories.join(", ")}. ` +
        "Never produce one of these.",
    );
  }

  lines.push(
    "",
    "PERSONALIZATION:",
    `- Respond in language code: ${input.languageCode}. Write the challenge ` +
      "entirely in that language.",
    `- Player country: ${input.countryCode}. Use locally appropriate references.`,
    input.age !== null
      ? `- Player age: ${input.age}. Tailor tone and content to this age.`
      : "- Player age unknown. Stay safe-for-all-ages.",
    input.interests.length > 0
      ? `- Player interests: ${input.interests.join(", ")}. Weave one in when natural.`
      : "- No declared interests; keep it broadly appealing.",
  );

  if (input.type === "DARE" && input.difficulty !== null) {
    lines.push(
      "",
      "DARE DIFFICULTY:",
      `- Match the difficulty EXACTLY to ${input.difficulty.target} on a 1..5 ` +
        `scale (group voted an average of ${input.difficulty.average.toFixed(2)}).`,
      "- 1 = trivial/very easy, 5 = bold/challenging but still safe and legal.",
      `- Respect the player's maximum allowed difficulty of ${input.limits.maxDifficulty}.`,
    );
  } else {
    lines.push(
      "",
      "TRUTH:",
      "- Produce a thoughtful or fun question. Truth has no difficulty rating.",
    );
  }

  lines.push(
    "",
    "UNIQUENESS:",
    "- The challenge must be original and NOT a paraphrase of anything the " +
      "player has recently seen.",
  );
  if (input.recentSignatures.length > 0) {
    lines.push(
      "- Avoid repeating any of these recent challenge signatures (sha1 of " +
        `normalized text): ${input.recentSignatures.slice(0, 50).join(", ")}.`,
    );
  }

  return lines.join("\n");
}

/** Normalizes text and hashes it so identical prompts share a stable signature. */
function computeSignature(text: string): string {
  const normalized = text.trim().toLowerCase().replace(/\s+/g, " ");
  return createHash("sha1").update(normalized).digest("hex");
}

function clampDifficulty(value: number): number {
  if (!Number.isFinite(value)) return DIFFICULTY_MIN;
  return Math.min(DIFFICULTY_MAX, Math.max(DIFFICULTY_MIN, Math.round(value)));
}

function coerceCategory(value: unknown): ChallengeCategory {
  return CHALLENGE_CATEGORIES.includes(value as ChallengeCategory)
    ? (value as ChallengeCategory)
    : "FUNNY";
}

/**
 * Calls Claude, extracts the structured `challenge` tool_use, and assembles the
 * `ChallengeOutput` the client expects. Throws on a malformed model response so
 * the caller can map it to an INTERNAL error.
 */
export async function generateChallenge(
  input: GenerateChallengeInput,
  apiKey: string,
): Promise<ChallengeOutput> {
  const anthropic = client(apiKey);

  const userPrompt =
    input.type === "DARE"
      ? "Generate one DARE challenge that matches the requirements."
      : "Generate one TRUTH question that matches the requirements.";

  const message = await anthropic.messages.create({
    model: MODEL,
    max_tokens: 1024,
    system: buildSystemPrompt(input),
    tools: [challengeTool],
    // Force the model to emit our structured tool so we always get JSON back.
    tool_choice: { type: "tool", name: CHALLENGE_TOOL },
    messages: [{ role: "user", content: userPrompt }],
  });

  const toolUse = message.content.find(
    (block): block is Anthropic.ToolUseBlock =>
      block.type === "tool_use" && block.name === CHALLENGE_TOOL,
  );
  if (toolUse === undefined) {
    throw new Error("Model did not return a challenge tool_use block.");
  }

  const result = toolUse.input as {
    text?: unknown;
    category?: unknown;
    isAdult?: unknown;
  };
  const text = typeof result.text === "string" ? result.text.trim() : "";
  if (text.length === 0) {
    throw new Error("Model returned an empty challenge text.");
  }

  // A minor (or a player without adult opt-in) can never receive adult content,
  // regardless of what the model claims — defense in depth.
  const minorOrNoAdult =
    (input.age !== null && input.age < 18) || !input.limits.allowAdult;
  const isAdult = minorOrNoAdult ? false : result.isAdult === true;

  const difficulty =
    input.type === "DARE" && input.difficulty !== null
      ? clampDifficulty(input.difficulty.target)
      : DIFFICULTY_MIN;

  return {
    id: randomUUID(),
    type: input.type,
    text,
    category: coerceCategory(result.category),
    difficulty,
    languageCode: input.languageCode,
    isAdult,
    signature: computeSignature(text),
    generatedAtEpochMs: Date.now(),
  };
}
