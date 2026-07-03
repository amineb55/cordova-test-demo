/**
 * Text moderation for chat messages and (defensively) generated challenges.
 *
 * Primary path: a Claude-based classifier (same SDK/model as challenge.ts) that
 * returns a structured verdict via a single `moderation` tool. Fallback path: a
 * deterministic rule-based classifier used when no API key is configured or the
 * model call fails — so moderation never silently disappears.
 *
 * Categories map exactly onto the domain `ModerationCategory` enum. Per spec we
 * ALWAYS BLOCK sexual-minors, hate, illegal, and self-harm regardless of the
 * model's own severity score.
 */

import Anthropic from "@anthropic-ai/sdk";
import {
  ModerationCategory,
  ModerationOutput,
  ModerationVerdict,
} from "./types";

const MODEL = "claude-haiku-4-5";
const MODERATION_TOOL = "moderation";

const ALL_CATEGORIES: readonly ModerationCategory[] = [
  "HATE",
  "HARASSMENT",
  "SEXUAL",
  "SEXUAL_MINORS",
  "VIOLENCE",
  "SELF_HARM",
  "ILLEGAL",
  "PERSONAL_INFO",
  "ADULT",
];

/** Categories that force a BLOCK no matter the score. */
const ALWAYS_BLOCK: readonly ModerationCategory[] = [
  "SEXUAL_MINORS",
  "HATE",
  "ILLEGAL",
  "SELF_HARM",
];

let cachedClient: Anthropic | null = null;

function client(apiKey: string): Anthropic {
  if (cachedClient === null) {
    cachedClient = new Anthropic({ apiKey });
  }
  return cachedClient;
}

const moderationTool: Anthropic.Tool = {
  name: MODERATION_TOOL,
  description:
    "Classify the provided text for safety. Always call this tool with the " +
    "structured verdict; never reply with prose.",
  input_schema: {
    type: "object",
    properties: {
      verdict: {
        type: "string",
        enum: ["ALLOW", "FLAG", "BLOCK"],
        description:
          "ALLOW = safe, FLAG = borderline/review, BLOCK = disallowed.",
      },
      categories: {
        type: "array",
        items: { type: "string", enum: ALL_CATEGORIES as unknown as string[] },
        description: "Every harmful category the text falls under (may be empty).",
      },
      score: {
        type: "number",
        description: "Severity from 0.0 (clearly safe) to 1.0 (clearly harmful).",
      },
      reason: {
        type: "string",
        description: "Short human-readable explanation of the verdict.",
      },
    },
    required: ["verdict", "categories", "score"],
    additionalProperties: false,
  },
};

/**
 * Applies the always-block policy and keeps verdict/score consistent. A model
 * could return BLOCK with a low score (or vice-versa); we normalize here.
 */
function applyPolicy(
  verdict: ModerationVerdict,
  categories: ModerationCategory[],
  score: number,
  reason?: string,
): ModerationOutput {
  const dedup = Array.from(new Set(categories)).filter((c) =>
    ALL_CATEGORIES.includes(c),
  );
  const mustBlock = dedup.some((c) => ALWAYS_BLOCK.includes(c));
  const finalVerdict: ModerationVerdict = mustBlock ? "BLOCK" : verdict;
  const finalScore = mustBlock
    ? Math.max(score, 0.95)
    : Math.min(1, Math.max(0, score));
  return {
    verdict: finalVerdict,
    categories: dedup,
    score: finalScore,
    reason: reason,
  };
}

/** Claude-based classifier. Throws on a malformed response. */
async function classifyWithClaude(
  text: string,
  apiKey: string,
): Promise<ModerationOutput> {
  const anthropic = client(apiKey);
  const message = await anthropic.messages.create({
    model: MODEL,
    max_tokens: 512,
    system:
      "You are a strict content-safety classifier for a Truth-or-Dare party " +
      "game. Classify the user's text and call the `moderation` tool. Always " +
      "BLOCK any sexual content involving minors, hate speech, instructions " +
      "for illegal acts, and self-harm encouragement.",
    tools: [moderationTool],
    tool_choice: { type: "tool", name: MODERATION_TOOL },
    messages: [{ role: "user", content: text }],
  });

  const toolUse = message.content.find(
    (block): block is Anthropic.ToolUseBlock =>
      block.type === "tool_use" && block.name === MODERATION_TOOL,
  );
  if (toolUse === undefined) {
    throw new Error("Model did not return a moderation tool_use block.");
  }

  const out = toolUse.input as {
    verdict?: unknown;
    categories?: unknown;
    score?: unknown;
    reason?: unknown;
  };
  const verdict: ModerationVerdict =
    out.verdict === "BLOCK" || out.verdict === "FLAG" ? out.verdict : "ALLOW";
  const categories = Array.isArray(out.categories)
    ? (out.categories.filter(
        (c): c is ModerationCategory =>
          typeof c === "string" &&
          ALL_CATEGORIES.includes(c as ModerationCategory),
      ) as ModerationCategory[])
    : [];
  const score = typeof out.score === "number" ? out.score : 0;
  const reason = typeof out.reason === "string" ? out.reason : undefined;

  return applyPolicy(verdict, categories, score, reason);
}

/**
 * Deterministic keyword fallback. Intentionally conservative: it errs toward
 * FLAG/BLOCK on strong signals and ALLOW otherwise. Not a substitute for the
 * model, but guarantees the endpoint always returns a sane, documented shape.
 */
function classifyWithRules(text: string): ModerationOutput {
  const lower = text.toLowerCase();
  const categories: ModerationCategory[] = [];

  const has = (words: string[]): boolean =>
    words.some((w) => lower.includes(w));

  // Order matters: minor-sexual must win over generic sexual.
  if (has(["child porn", "underage", "minor sex", "cp ", "loli"])) {
    categories.push("SEXUAL_MINORS");
  }
  if (has(["kill yourself", "kys", "suicide", "self harm", "self-harm"])) {
    categories.push("SELF_HARM");
  }
  if (has(["n-word", "faggot", "kike", "spic", "racial slur"])) {
    categories.push("HATE");
  }
  if (has(["buy drugs", "how to make a bomb", "steal a car", "counterfeit"])) {
    categories.push("ILLEGAL");
  }
  if (has(["idiot", "stupid", "loser", "shut up", "ugly"])) {
    categories.push("HARASSMENT");
  }
  if (has(["nude", "naked", "porn", "sex", "nsfw"])) {
    categories.push("SEXUAL");
    categories.push("ADULT");
  }
  if (has(["kill", "stab", "shoot", "blood", "violence"])) {
    categories.push("VIOLENCE");
  }
  // Naive PII signals: emails and long digit runs (phone numbers).
  if (/[\w.+-]+@[\w-]+\.[\w.-]+/.test(text) || /\d{7,}/.test(text)) {
    categories.push("PERSONAL_INFO");
  }

  if (categories.length === 0) {
    return { verdict: "ALLOW", categories: [], score: 0, reason: undefined };
  }

  const mustBlock = categories.some((c) => ALWAYS_BLOCK.includes(c));
  const verdict: ModerationVerdict = mustBlock ? "BLOCK" : "FLAG";
  const score = mustBlock ? 0.95 : 0.6;
  return applyPolicy(verdict, categories, score, "Rule-based classifier match.");
}

/**
 * Public entry point. Prefers Claude when `apiKey` is present and reachable,
 * otherwise (or on error) falls back to the rule-based classifier.
 */
export async function moderateText(
  text: string,
  apiKey: string | undefined,
): Promise<ModerationOutput> {
  const trimmed = text.trim();
  if (trimmed.length === 0) {
    return { verdict: "ALLOW", categories: [], score: 0 };
  }
  if (apiKey === undefined || apiKey.length === 0) {
    return classifyWithRules(trimmed);
  }
  try {
    return await classifyWithClaude(trimmed, apiKey);
  } catch (err) {
    // Never fail open silently — fall back to deterministic rules.
    console.error("Claude moderation failed; using rule-based fallback.", err);
    return classifyWithRules(trimmed);
  }
}
