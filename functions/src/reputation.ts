/**
 * Post-game peer reputation (spec "RÉPUTATION").
 *
 * At the end of a game each player rates the others 1..5. Ratings are de-duped
 * per (rater, target, room) and folded into each target's running-average
 * `reputation` field on their user doc; the tier is recomputed with the same
 * thresholds as the domain `ReputationCalculator`. Reputation then feeds
 * matchmaking priority.
 */

import * as admin from "firebase-admin";
import {
  COLLECTION_ROOMS,
  COLLECTION_USERS,
  SUBCOLLECTION_RATINGS,
  reputationTierFor,
} from "./types";

interface IncomingRating {
  targetUid: string;
  score: number;
}

function parseRatings(raw: unknown): IncomingRating[] {
  if (!Array.isArray(raw)) return [];
  const out: IncomingRating[] = [];
  for (const r of raw) {
    const targetUid = typeof r?.targetUid === "string" ? r.targetUid : "";
    const score = typeof r?.score === "number" ? Math.round(r.score) : NaN;
    if (targetUid.length > 0 && score >= 1 && score <= 5) {
      out.push({ targetUid, score });
    }
  }
  return out;
}

/**
 * Records [raterUid]'s ratings for [roomId] and updates each target's aggregate
 * reputation. Each (rater, target, room) tuple can only be rated once.
 */
export async function submitRatings(
  raterUid: string,
  roomId: string,
  rawRatings: unknown,
): Promise<void> {
  const ratings = parseRatings(rawRatings).filter((r) => r.targetUid !== raterUid);
  if (ratings.length === 0) return;

  const db = admin.firestore();
  const roomRatings = db
    .collection(COLLECTION_ROOMS)
    .doc(roomId)
    .collection(SUBCOLLECTION_RATINGS);

  await Promise.all(
    ratings.map((rating) =>
      db.runTransaction(async (tx) => {
        const ratingId = `${raterUid}_${rating.targetUid}`;
        const ratingRef = roomRatings.doc(ratingId);
        const existing = await tx.get(ratingRef);
        if (existing.exists) return; // already rated this target in this room

        const targetRef = db.collection(COLLECTION_USERS).doc(rating.targetUid);
        const targetSnap = await tx.get(targetRef);
        const rep = (targetSnap.get("reputation") as Record<string, unknown> | undefined) ?? {};
        const prevCount = typeof rep.ratingsCount === "number" ? rep.ratingsCount : 0;
        const prevAvg = typeof rep.averageScore === "number" ? rep.averageScore : 0;

        const newCount = prevCount + 1;
        const newAvg = (prevAvg * prevCount + rating.score) / newCount;

        tx.set(ratingRef, {
          raterUid,
          targetUid: rating.targetUid,
          score: rating.score,
          roomId,
          createdAtEpochMs: Date.now(),
        });
        tx.set(
          targetRef,
          {
            reputation: {
              averageScore: newAvg,
              ratingsCount: newCount,
              tier: reputationTierFor(newAvg, newCount),
            },
          },
          { merge: true },
        );
      }),
    ),
  );
}
