import type { Locale } from "./config";

// Règles produit (brief §5) respectées dans chaque texte : marque blanche
// (« le moteur Earnaura », jamais un fournisseur), aucune promesse de
// viralité, scores toujours « estimation non calibrée ».

const fr = {
  meta: {
    titre: "Earnaura",
    description:
      "Sache quoi créer. Sache comment l'améliorer. Sache quand publier. Le moteur Earnaura analyse tes vidéos courtes et te rend des plans d'action et des idées prêtes à tourner, dans ta langue.",
  },
  entete: { tarifs: "Tarifs", faq: "FAQ", cta: "Analyse ta vidéo" },
  hero: {
    eyebrow: "Pour les créateurs de contenu",
    titre1: "Sache quoi créer.",
    titre2: "Sache comment l'améliorer.",
    titre3: "Sache quand publier.",
    sousTitre:
      "Téléverse une vidéo — la tienne ou une qui t'inspire. Le moteur Earnaura la regarde, l'écoute, la mesure, puis te rend un diagnostic avec preuves et des fiches idées prêtes à tourner, dans ta langue — darija comprise.",
    ctaPrincipal: "Analyse ta vidéo gratuitement",
    ctaSecondaire: "Voir les tarifs",
    honnete: "Aucune promesse de vues. Des données, des preuves, un plan d'action.",
  },
  etapes: {
    titre: "Comment ça marche",
    e1t: "Téléverse ta vidéo",
    e1d: "La tienne pour un diagnostic, ou une vidéo qui t'inspire pour en décoder la formule. Jusqu'à 3 minutes.",
    e2t: "Le moteur Earnaura l'analyse",
    e2d: "Il regarde, écoute et mesure : rythme, son, zones où l'attention décroche, hook, structure — chaque constat cite sa preuve.",
    e3t: "Reçois ton plan d'action",
    e3d: "Verdict, corrections horodatées, hooks réécrits dans ta langue, et 5 fiches idées adaptées à ta niche et à ton pays.",
  },
  fiches: {
    titre: "Les fiches idées",
    sousTitre:
      "Chaque fiche applique le mécanisme d'une vidéo qui marche à TA niche — jamais une copie. Script prêt à lire, textes écran, plan de tournage adapté à ton matériel, CTA.",
    exempleTitre: "طريقة باش تعرف الجاثوم زارك البارح 😴👹",
    exempleType: "Fiche idée · storytelling",
    exempleScore: "Score 82/100 — estimation non calibrée",
    scriptLabel: "Script complet",
    planLabel: "Plan de tournage",
    ctaFlou: "Débloquer avec Pro",
    duree: "Durée cible : 45 s",
  },
  securite: {
    titre: "La sécurité de ton compte d'abord",
    sousTitre:
      "On informe, on ne juge jamais une niche. Avant que tu publies, Earnaura signale ce qui peut toucher aux règles des plateformes.",
    w1t: "Warnings de conformité",
    w1d: "Niveau FAIBLE / À VÉRIFIER / ÉLEVÉ, la raison, et la date des règles publiques consultées. Un risque, jamais un verdict.",
    w2t: "Tes vidéos restent à toi",
    w2d: "Privées par défaut, jamais utilisées pour entraîner quoi que ce soit, supprimées automatiquement après 30 jours — ou tout de suite, d'un bouton.",
    w3t: "Honnêteté par conception",
    w3d: "Aucun score inventé, aucune statistique fabriquée : chaque recommandation cite la donnée qui la justifie, et tout score porte « estimation non calibrée ».",
  },
  tarifs: {
    titre: "Tarifs",
    sousTitre: "1 crédit = jusqu'à 5 minutes de vidéo analysée en profondeur.",
    zoneMaghreb: "Maroc · Maghreb · Égypte",
    zoneIntl: "Europe · Golfe · International",
    parMois: "/mois",
    populaire: "Le plus choisi",
    gratuitNom: "Express",
    gratuitPrix: "0",
    gratuitPts: [
      "Analyses Express illimitées (diagnostic technique + mini-résumé)",
      "1 Analyse Complète offerte à l'inscription",
      "Vidéos jusqu'à 90 s en Express",
    ],
    proNom: "Pro",
    proPts: [
      "Crédits d'Analyse Complète chaque mois",
      "Fiches idées, hooks réécrits, formule décodée",
      "Rapports sans filigrane",
    ],
    creatorNom: "Creator",
    creatorPts: [
      "≈ 100 crédits par mois",
      "Bilan de compte et avant/après",
      "Tout Pro inclus",
    ],
    studioNom: "Studio",
    studioPrix: "99 €",
    studioNote: "prix unique, toutes zones",
    studioPts: [
      "Le modèle le plus avancé sur chaque analyse",
      "Analyse Profonde : 4 directions créatives, 10 fiches, storyboard",
      "File prioritaire · 5 comptes",
    ],
    creditsPro: { maghreb: "15-20 crédits", intl: "30 crédits" },
    prixPro: { maghreb: "49 MAD", intl: "9,99 €" },
    prixCreator: { maghreb: "119 MAD", intl: "24,99 €" },
    mention:
      "Recharges de crédits à l'unité. Analyse Studio à la carte : ×5 crédits depuis Pro ou Creator.",
    cta: "Commencer gratuitement",
  },
  faq: {
    titre: "Questions fréquentes",
    q1: "C'est quoi, une Analyse Express ?",
    r1: "Le diagnostic technique gratuit : timeline d'attention, zones où l'attention décroche, coupures, niveau sonore, et un mini-résumé. La solution complète (fiches, hooks, formule) est réservée aux Analyses Complètes.",
    q2: "Mes vidéos sont-elles privées ?",
    r2: "Oui. Privées par défaut, jamais utilisées pour entraîner des modèles, supprimées automatiquement après 30 jours — et tu peux les supprimer immédiatement depuis ton compte.",
    q3: "Quelles langues sont comprises ?",
    r3: "Toutes à égalité — darija, arabe, français, anglais et leurs mélanges. La darija n'est jamais « corrigée » vers l'arabe classique : tes hooks et scripts sortent dans ta langue, telle que tu la parles.",
    q4: "Vous promettez des vues ?",
    r4: "Non, et personne d'honnête ne le peut. Earnaura te donne des données mesurées, des preuves et des plans d'action. Tous les scores affichés sont des estimations non calibrées.",
    q5: "Comment fonctionnent les crédits ?",
    r5: "1 crédit couvre jusqu'à 5 minutes de vidéo en Analyse Complète. Les crédits sont réservés au lancement puis ajustés à la durée réelle. Des recharges à l'unité existent si tu en veux plus.",
    q6: "Et pour découper mes vidéos longues en shorts ?",
    r6: "Le Moteur Découpe (vidéo longue → shorts 9:16 sous-titrés, classés par potentiel) existe déjà et arrive dans l'application juste après le lancement.",
  },
  apercu: {
    badge: "Préversion",
    titre: "L'application arrive",
    texte:
      "Cette préversion présente Earnaura. Le parcours complet — téléverser, analyser, recevoir ton rapport en ligne — ouvre très bientôt.",
    retour: "Retour à l'accueil",
  },
  pied: {
    contact: "hello@earnaura.ai",
    droits: "© 2026 Earnaura — tous droits réservés.",
    preversion: "Préversion — earnaura.ai",
  },
};

type Dictionnaire = typeof fr;

const en: Dictionnaire = {
  meta: {
    titre: "Earnaura",
    description:
      "Know what to create. Know how to improve it. Know when to post. The Earnaura engine analyzes your short videos and returns action plans and ready-to-shoot ideas, in your language.",
  },
  entete: { tarifs: "Pricing", faq: "FAQ", cta: "Analyze your video" },
  hero: {
    eyebrow: "For content creators",
    titre1: "Know what to create.",
    titre2: "Know how to improve it.",
    titre3: "Know when to post.",
    sousTitre:
      "Upload a video — yours, or one that inspires you. The Earnaura engine watches it, listens to it, measures it, then returns an evidence-backed diagnosis and ready-to-shoot idea cards, in your language — Darija included.",
    ctaPrincipal: "Analyze your video for free",
    ctaSecondaire: "See pricing",
    honnete: "No promise of views. Data, evidence, and an action plan.",
  },
  etapes: {
    titre: "How it works",
    e1t: "Upload your video",
    e1d: "Yours for a diagnosis, or a video that inspires you to decode its formula. Up to 3 minutes.",
    e2t: "The Earnaura engine analyzes it",
    e2d: "It watches, listens and measures: pacing, sound, attention drop zones, hook, structure — every finding cites its evidence.",
    e3t: "Get your action plan",
    e3d: "A verdict, time-coded fixes, rewritten hooks in your language, and 5 idea cards tailored to your niche and country.",
  },
  fiches: {
    titre: "Idea cards",
    sousTitre:
      "Each card applies the mechanism of a video that works to YOUR niche — never a copy. Read-aloud script, on-screen text, a shooting plan matched to your gear, and a CTA.",
    exempleTitre: "طريقة باش تعرف الجاثوم زارك البارح 😴👹",
    exempleType: "Idea card · storytelling",
    exempleScore: "Score 82/100 — uncalibrated estimate",
    scriptLabel: "Full script",
    planLabel: "Shooting plan",
    ctaFlou: "Unlock with Pro",
    duree: "Target length: 45 s",
  },
  securite: {
    titre: "Your account's safety first",
    sousTitre:
      "We inform, we never judge a niche. Before you post, Earnaura flags anything that may touch platform rules.",
    w1t: "Compliance warnings",
    w1d: "LOW / CHECK / HIGH level, the reason, and the date of the public rules consulted. A risk, never a verdict.",
    w2t: "Your videos stay yours",
    w2d: "Private by default, never used to train anything, automatically deleted after 30 days — or instantly, with one button.",
    w3t: "Honesty by design",
    w3d: "No invented scores, no fabricated statistics: every recommendation cites the data behind it, and every score is an uncalibrated estimate.",
  },
  tarifs: {
    titre: "Pricing",
    sousTitre: "1 credit = up to 5 minutes of video, analyzed in depth.",
    zoneMaghreb: "Morocco · Maghreb · Egypt",
    zoneIntl: "Europe · Gulf · International",
    parMois: "/month",
    populaire: "Most popular",
    gratuitNom: "Express",
    gratuitPrix: "0",
    gratuitPts: [
      "Unlimited Express analyses (technical diagnosis + mini-summary)",
      "1 free Full Analysis when you sign up",
      "Videos up to 90 s in Express",
    ],
    proNom: "Pro",
    proPts: [
      "Full Analysis credits every month",
      "Idea cards, rewritten hooks, decoded formula",
      "Watermark-free reports",
    ],
    creatorNom: "Creator",
    creatorPts: [
      "≈ 100 credits per month",
      "Account review and before/after",
      "Everything in Pro",
    ],
    studioNom: "Studio",
    studioPrix: "€99",
    studioNote: "single price, all zones",
    studioPts: [
      "The most advanced model on every analysis",
      "Deep Analysis: 4 creative directions, 10 cards, storyboard",
      "Priority queue · 5 seats",
    ],
    creditsPro: { maghreb: "15-20 credits", intl: "30 credits" },
    prixPro: { maghreb: "49 MAD", intl: "€9.99" },
    prixCreator: { maghreb: "119 MAD", intl: "€24.99" },
    mention:
      "Credit top-ups available. Studio Analysis à la carte: ×5 credits from Pro or Creator.",
    cta: "Start for free",
  },
  faq: {
    titre: "Frequently asked questions",
    q1: "What is an Express Analysis?",
    r1: "The free technical diagnosis: attention timeline, drop zones, cuts, loudness, and a mini-summary. The full solution (idea cards, hooks, formula) belongs to Full Analyses.",
    q2: "Are my videos private?",
    r2: "Yes. Private by default, never used to train models, automatically deleted after 30 days — and you can delete them instantly from your account.",
    q3: "Which languages are understood?",
    r3: "All of them, equally — Darija, Arabic, French, English and their mixes. Darija is never “corrected” into classical Arabic: your hooks and scripts come out in your language, the way you speak it.",
    q4: "Do you promise views?",
    r4: "No — and nobody honest can. Earnaura gives you measured data, evidence and action plans. Every displayed score is an uncalibrated estimate.",
    q5: "How do credits work?",
    r5: "1 credit covers up to 5 minutes of video in Full Analysis. Credits are reserved when a job starts, then adjusted to the real duration. Top-ups are available.",
    q6: "What about cutting my long videos into shorts?",
    r6: "The Clipping Engine (long video → subtitled 9:16 shorts, ranked by potential) already exists and lands in the app right after launch.",
  },
  apercu: {
    badge: "Preview",
    titre: "The app is coming",
    texte:
      "This preview introduces Earnaura. The full journey — upload, analyze, get your report online — opens very soon.",
    retour: "Back to home",
  },
  pied: {
    contact: "hello@earnaura.ai",
    droits: "© 2026 Earnaura — all rights reserved.",
    preversion: "Preview — earnaura.ai",
  },
};

const dictionnaires: Record<Locale, Dictionnaire> = { fr, en };

export function dictionnaire(locale: Locale): Dictionnaire {
  return dictionnaires[locale];
}
export type { Dictionnaire };
