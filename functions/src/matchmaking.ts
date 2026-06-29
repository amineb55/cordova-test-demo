/**
 * Server-authoritative matchmaking.
 *
 * Reads the `matchQueue` collection (documents written by the Android client's
 * `MatchmakingRepositoryImpl.toQueueMap`), scores every candidate against the
 * seeker with the SAME weighted formula as the on-device `MatchmakingScorer`,
 * greedily forms a group of up to `desiredSize`, writes a `rooms` document the
 * client can deserialize into `RoomDto`, and removes the matched players from
 * the queue. Returns `{ roomId }` (or `{ roomId: null }` while still searching).
 *
 * Simplifications (documented intentionally):
 *  - No global optimization; a greedy top-N selection around the seeker is
 *    sufficient for the pool sizes this game sees and keeps the call cheap.
 *  - Concurrency is handled with a Firestore transaction over the chosen docs;
 *    if a candidate was claimed by a parallel match it is dropped and the group
 *    may end up smaller (still >= MIN_PLAYERS or we return null).
 *  - `pingMs` is read straight from the queue doc (client-measured).
 */

import * as admin from "firebase-admin";
import { randomUUID } from "crypto";
import {
  COLLECTION_MATCH_QUEUE,
  COLLECTION_ROOMS,
  Interest,
  RequestMatchInput,
  ROOM_MAX_PLAYERS,
  ROOM_MIN_PLAYERS,
} from "./types";

/** Scorer weights — must stay identical to `MatchmakingScorer` (domain). */
const W_LANGUAGE = 0.3;
const W_INTERESTS = 0.25;
const W_LEVEL = 0.15;
const W_AGE = 0.1;
const W_COUNTRY = 0.1;
const W_PING = 0.1;

/** Default gap/ping bounds — mirror `MatchPreferences` defaults. */
const MAX_LEVEL_GAP = 10;
const MAX_AGE_GAP = 8;
const MAX_PING_MS = 300;

/** A candidate as stored in `matchQueue`. */
interface QueueEntry {
  uid: string;
  languageCode: string;
  countryCode: string;
  age: number | null;
  level: number;
  interests: Interest[];
  allowAdult: boolean;
  pingMs: number;
  desiredSize: number;
}

function parseEntry(
  doc: admin.firestore.QueryDocumentSnapshot,
): QueueEntry | null {
  const data = doc.data();
  const uid = (data.uid as string | undefined) ?? doc.id;
  if (typeof uid !== "string" || uid.length === 0) return null;
  const rawInterests = Array.isArray(data.interests) ? data.interests : [];
  return {
    uid,
    languageCode: (data.languageCode as string) ?? "en",
    countryCode: (data.countryCode as string) ?? "US",
    age: typeof data.age === "number" ? data.age : null,
    level: typeof data.level === "number" ? data.level : 1,
    interests: rawInterests.filter(
      (i: unknown): i is Interest => typeof i === "string",
    ),
    allowAdult: data.allowAdult === true,
    pingMs: typeof data.pingMs === "number" ? data.pingMs : 0,
    desiredSize:
      typeof data.desiredSize === "number"
        ? data.desiredSize
        : ROOM_MIN_PLAYERS,
  };
}

function jaccard(a: Interest[], b: Interest[]): number {
  const sa = new Set(a);
  const sb = new Set(b);
  if (sa.size === 0 && sb.size === 0) return 0;
  let intersection = 0;
  for (const x of sa) if (sb.has(x)) intersection++;
  const union = new Set([...sa, ...sb]).size;
  return union === 0 ? 0 : intersection / union;
}

/** 1.0 when gap is 0, decaying linearly to 0.0 at maxGap. */
function proximity(gap: number, maxGap: number): number {
  if (maxGap <= 0) return gap === 0 ? 1 : 0;
  return Math.max(0, Math.min(1, 1 - gap / maxGap));
}

function ageScore(selfAge: number | null, candidateAge: number | null): number {
  if (selfAge === null || candidateAge === null) return 0.5;
  return proximity(Math.abs(selfAge - candidateAge), MAX_AGE_GAP);
}

/** Compatibility on a 0..1 scale — identical formula to `MatchmakingScorer`. */
export function score(self: QueueEntry, candidate: QueueEntry): number {
  const language = self.languageCode === candidate.languageCode ? 1 : 0;
  const country = self.countryCode === candidate.countryCode ? 1 : 0;
  const interests = jaccard(self.interests, candidate.interests);
  const level = proximity(Math.abs(self.level - candidate.level), MAX_LEVEL_GAP);
  const age = ageScore(self.age, candidate.age);
  const ping = 1 - Math.min(1, Math.max(0, candidate.pingMs) / MAX_PING_MS);

  const total =
    W_LANGUAGE * language +
    W_INTERESTS * interests +
    W_LEVEL * level +
    W_AGE * age +
    W_COUNTRY * country +
    W_PING * ping;
  return Math.max(0, Math.min(1, total));
}

/** Applies the seeker's hard filters from `RequestMatchInput`. */
function passesHardFilters(
  self: QueueEntry,
  candidate: QueueEntry,
  prefs: RequestMatchInput,
): boolean {
  if (candidate.uid === self.uid) return false;
  if ((prefs.sameLanguageOnly ?? true) && candidate.languageCode !== self.languageCode) {
    return false;
  }
  if ((prefs.sameCountryOnly ?? false) && candidate.countryCode !== self.countryCode) {
    return false;
  }
  if ((prefs.adultOnly ?? false) && !candidate.allowAdult) return false;
  return true;
}

/** Builds a `RoomDto`-shaped document from the chosen players. */
function buildRoomDoc(
  roomId: string,
  members: QueueEntry[],
  hostUid: string,
): Record<string, unknown> {
  const now = Date.now();
  const players = members.map((m) => ({
    uid: m.uid,
    displayName: "",
    photoUrl: null,
    level: m.level,
    micEnabled: true,
    cameraEnabled: true,
    speaking: false,
    pingMs: m.pingMs,
    connection: "GOOD",
    isHost: m.uid === hostUid,
    joinedAtEpochMs: now,
  }));
  const host = members.find((m) => m.uid === hostUid) ?? members[0];
  return {
    id: roomId,
    hostUid,
    visibility: "PUBLIC",
    status: "WAITING",
    players,
    languageCode: host.languageCode,
    countryCode: host.countryCode,
    adultOnly: members.every((m) => m.allowAdult),
    inviteCode: null,
    maxPlayers: ROOM_MAX_PLAYERS,
    createdAtEpochMs: now,
  };
}

/**
 * Core matchmaking. Returns the new room id, or `null` when not enough
 * compatible players are queued yet (the client keeps waiting).
 */
export async function requestMatch(
  uid: string,
  prefs: RequestMatchInput,
): Promise<string | null> {
  const db = admin.firestore();
  const queueRef = db.collection(COLLECTION_MATCH_QUEUE);

  // Bounded snapshot — same 50-doc cap the client uses for its local pool.
  const snapshot = await queueRef.limit(50).get();
  const entries = snapshot.docs
    .map(parseEntry)
    .filter((e): e is QueueEntry => e !== null);

  const self = entries.find((e) => e.uid === uid);
  if (self === undefined) {
    // Caller isn't in the queue (race with enqueue) — nothing to match yet.
    return null;
  }

  const desiredSize = Math.min(
    ROOM_MAX_PLAYERS,
    Math.max(ROOM_MIN_PLAYERS, prefs.desiredSize ?? self.desiredSize),
  );

  const ranked = entries
    .filter((c) => passesHardFilters(self, c, prefs))
    .map((c) => ({ entry: c, s: score(self, c) }))
    .sort((a, b) => b.s - a.s);

  // Seeker first, then the best-scoring candidates up to desiredSize.
  const group: QueueEntry[] = [self];
  for (const { entry } of ranked) {
    if (group.length >= desiredSize) break;
    group.push(entry);
  }

  if (group.length < ROOM_MIN_PLAYERS) {
    // Not enough compatible players yet — keep the seeker queued.
    return null;
  }

  const roomId = randomUUID();
  const roomRef = db.collection(COLLECTION_ROOMS).doc(roomId);
  const memberRefs = group.map((m) => queueRef.doc(m.uid));

  // Claim the chosen players atomically; drop any that vanished mid-flight.
  const committedMembers = await db.runTransaction(async (tx) => {
    const docs = await Promise.all(memberRefs.map((ref) => tx.get(ref)));
    const present: QueueEntry[] = [];
    for (let i = 0; i < docs.length; i++) {
      if (docs[i].exists) present.push(group[i]);
    }
    if (present.length < ROOM_MIN_PLAYERS) {
      return [] as QueueEntry[];
    }
    const room = buildRoomDoc(roomId, present, self.uid);
    tx.set(roomRef, room);
    // Remove matched players from the queue so they aren't re-matched.
    for (const m of present) tx.delete(queueRef.doc(m.uid));
    return present;
  });

  return committedMembers.length >= ROOM_MIN_PLAYERS ? roomId : null;
}
