/**
 * Server-authoritative Gold economy (spec "ÉCONOMIE GOLD" / "MONÉTISATION").
 *
 * Clients never write their own balance; every change runs here inside a
 * Firestore transaction against `users/{uid}.gold`, with an append-only ledger
 * entry under `users/{uid}/transactions`. Amounts come from the `config/economy`
 * document (Remote Config's server twin) falling back to {@link ECONOMY_DEFAULTS},
 * so prices/rewards are tunable without an app release.
 *
 * Balances are clamped at zero: a refusal penalty larger than the balance simply
 * zeroes it ("Si le joueur possède moins de 3 Gold, son solde devient 0").
 */

import * as admin from "firebase-admin";
import { randomUUID } from "crypto";
import {
  COLLECTION_USERS,
  ECONOMY_DEFAULTS,
  SUBCOLLECTION_TRANSACTIONS,
} from "./types";

export type GoldReason =
  | "GAME_JOIN"
  | "CHALLENGE_SUCCESS"
  | "REFUSAL"
  | "REWARDED_VIDEO"
  | "PURCHASE"
  | "ADMIN_GRANT";

interface EconomyConfig {
  joinCost: number;
  rewardSuccess: number;
  penaltyRefuse: number;
  rewardedVideoGold: number;
}

/** Reads `config/economy`, falling back to compiled defaults. */
async function loadConfig(db: admin.firestore.Firestore): Promise<EconomyConfig> {
  const snap = await db.collection("config").doc("economy").get();
  const data = snap.exists ? snap.data() ?? {} : {};
  return {
    joinCost: numberOr(data.joinCost, ECONOMY_DEFAULTS.joinCost),
    rewardSuccess: numberOr(data.rewardSuccess, ECONOMY_DEFAULTS.rewardSuccess),
    penaltyRefuse: numberOr(data.penaltyRefuse, ECONOMY_DEFAULTS.penaltyRefuse),
    rewardedVideoGold: numberOr(data.rewardedVideoGold, ECONOMY_DEFAULTS.rewardedVideoGold),
  };
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

/**
 * Atomically applies a Gold delta to a user, clamped at zero, and records a
 * ledger entry. Returns the new balance.
 *
 * @param requireFunds when true (game entry), throws if the balance is below the
 *        absolute value of a negative delta.
 */
async function applyDelta(
  uid: string,
  delta: number,
  reason: GoldReason,
  meta: Record<string, unknown>,
  requireFunds = false,
): Promise<number> {
  const db = admin.firestore();
  const userRef = db.collection(COLLECTION_USERS).doc(uid);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const current = numberOr(snap.get("gold"), 0);
    if (requireFunds && delta < 0 && current < -delta) {
      throw new Error("INSUFFICIENT_FUNDS");
    }
    const next = Math.max(0, current + delta);
    const applied = next - current;
    tx.set(userRef, { gold: next }, { merge: true });
    tx.set(userRef.collection(SUBCOLLECTION_TRANSACTIONS).doc(randomUUID()), {
      delta: applied,
      reason,
      balanceAfter: next,
      createdAtEpochMs: Date.now(),
      ...meta,
    });
    return next;
  });
}

export async function chargeGameFee(uid: string, roomId: string): Promise<number> {
  const cfg = await loadConfig(admin.firestore());
  return applyDelta(uid, -cfg.joinCost, "GAME_JOIN", { roomId }, /* requireFunds */ true);
}

export async function rewardChallenge(uid: string, roomId: string): Promise<number> {
  const cfg = await loadConfig(admin.firestore());
  return applyDelta(uid, cfg.rewardSuccess, "CHALLENGE_SUCCESS", { roomId });
}

export async function penalizeRefusal(uid: string, roomId: string): Promise<number> {
  const cfg = await loadConfig(admin.firestore());
  return applyDelta(uid, -cfg.penaltyRefuse, "REFUSAL", { roomId });
}

/**
 * Credits a rewarded-video grant. The {@link ssvToken} should be validated
 * against the ad network's Server-Side Verification callback before granting;
 * that exchange is provider-specific and left as a documented integration point.
 */
export async function grantRewardedVideo(uid: string, ssvToken: string): Promise<number> {
  if (ssvToken.length === 0) throw new Error("MISSING_SSV_TOKEN");
  // TODO(integration): verify ssvToken with the ad network's SSV endpoint.
  const cfg = await loadConfig(admin.firestore());
  return applyDelta(uid, cfg.rewardedVideoGold, "REWARDED_VIDEO", { ssv: true });
}

/**
 * Validates a store purchase and credits the pack's Gold. The {@link purchaseToken}
 * must be verified with Google Play Developer API (productId -> pack) before
 * granting; that call is left as a documented integration point. Pack -> gold is
 * read from `config/economy.packs`.
 */
export async function purchasePack(
  uid: string,
  packId: string,
  purchaseToken: string,
): Promise<number> {
  if (purchaseToken.length === 0) throw new Error("MISSING_PURCHASE_TOKEN");
  // TODO(integration): verify purchaseToken with Google Play before granting.
  const db = admin.firestore();
  const snap = await db.collection("config").doc("economy").get();
  const packs = (snap.get("packs") as Array<Record<string, unknown>> | undefined) ?? [];
  const pack = packs.find((p) => p.id === packId);
  const gold = numberOr(pack?.gold, 0);
  if (gold <= 0) throw new Error("UNKNOWN_PACK");
  return applyDelta(uid, gold, "PURCHASE", { packId });
}
