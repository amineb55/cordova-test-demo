"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { Dictionnaire } from "@/i18n/dictionnaires";
import type { Locale } from "@/i18n/config";
import Rapport from "./Rapport";

const API = process.env.NEXT_PUBLIC_API_URL ?? "";
const CLE_CODE = "earnaura_code";

type Phase = "code" | "depot" | "envoi" | "analyse" | "rapport" | "erreur";

// Les étapes annoncées correspondent aux étages réellement franchis par
// le moteur : le worker les déduit de sa sortie, rien n'est simulé.
const ETAPES = ["ingestion", "transcription", "comprehension", "formule",
                "generation", "rapport"] as const;

function libelleEtape(cle: string, locale: Locale): string {
  const fr: Record<string, string> = {
    ingestion: "Lecture de ta vidéo…",
    transcription: "Analyse du son et de la parole…",
    comprehension: "Compréhension de ta langue…",
    formule: "Décodage de la formule…",
    generation: "Rédaction de tes recommandations…",
    rapport: "Mise en forme du rapport…",
  };
  const en: Record<string, string> = {
    ingestion: "Reading your video…",
    transcription: "Analyzing sound and speech…",
    comprehension: "Understanding your language…",
    formule: "Decoding the formula…",
    generation: "Writing your recommendations…",
    rapport: "Formatting your report…",
  };
  return (locale === "en" ? en : fr)[cle] ?? cle;
}

export default function Analyse({
  d,
  locale,
}: {
  d: Dictionnaire;
  locale: Locale;
}) {
  const [phase, setPhase] = useState<Phase>("code");
  const [code, setCode] = useState("");
  const [saisieCode, setSaisieCode] = useState("");
  const [fichier, setFichier] = useState<File | null>(null);
  const [mode, setMode] = useState<"ma-video" | "inspiration">("ma-video");
  const [tiktok, setTiktok] = useState(false);
  const [survol, setSurvol] = useState(false);
  const [identifiant, setIdentifiant] = useState<string | null>(null);
  const [etape, setEtape] = useState<string>("ingestion");
  const [rapport, setRapport] = useState<Record<string, unknown> | null>(null);
  const [erreur, setErreur] = useState<string>("");
  const champFichier = useRef<HTMLInputElement>(null);

  // Le code d'accès du mode fondateur est mémorisé sur cet appareil.
  useEffect(() => {
    try {
      const enregistre = localStorage.getItem(CLE_CODE);
      if (enregistre) {
        setCode(enregistre);
        setPhase("depot");
      }
    } catch {
      /* stockage indisponible : on demandera le code à chaque visite */
    }
  }, []);

  const enteteCode = useCallback(
    (): Record<string, string> =>
      code ? { "x-code-fondateur": code } : {},
    [code],
  );

  // Sondage du statut tant que l'analyse tourne.
  useEffect(() => {
    if (phase !== "analyse" || !identifiant) return;
    let actif = true;
    const minuteur = setInterval(async () => {
      try {
        const reponse = await fetch(`${API}/analyses/${identifiant}`, {
          headers: enteteCode(),
        });
        if (!reponse.ok) throw new Error(String(reponse.status));
        const statut = await reponse.json();
        if (!actif) return;
        setEtape(statut.etape ?? "ingestion");
        if (statut.statut === "erreur") {
          setErreur(statut.erreur || d.analyse.erreurTitre);
          setPhase("erreur");
        } else if (statut.statut === "termine") {
          const brut = await fetch(`${API}/analyses/${identifiant}/rapport`, {
            headers: enteteCode(),
          });
          if (!brut.ok) throw new Error(String(brut.status));
          setRapport(await brut.json());
          setPhase("rapport");
        }
      } catch {
        if (!actif) return;
        setErreur(d.analyse.apiIndisponible);
        setPhase("erreur");
      }
    }, 3000);
    return () => {
      actif = false;
      clearInterval(minuteur);
    };
  }, [phase, identifiant, enteteCode, d.analyse]);

  function choisirFichier(liste: FileList | null) {
    const choisi = liste?.[0];
    if (choisi) setFichier(choisi);
  }

  async function lancer() {
    if (!fichier) return;
    setPhase("envoi");
    setErreur("");
    const donnees = new FormData();
    donnees.append("video", fichier);
    donnees.append("mode", mode);
    donnees.append("crop_haut", tiktok ? "0.12" : "0");
    donnees.append("crop_bas", tiktok ? "0.15" : "0");
    try {
      const reponse = await fetch(`${API}/analyses`, {
        method: "POST",
        headers: enteteCode(),
        body: donnees,
      });
      if (reponse.status === 401) {
        try {
          localStorage.removeItem(CLE_CODE);
        } catch {
          /* ignoré */
        }
        setCode("");
        setErreur(d.analyse.codeErreur);
        setPhase("code");
        return;
      }
      if (!reponse.ok) {
        const details = await reponse.json().catch(() => ({}));
        setErreur(details.detail || d.analyse.apiIndisponible);
        setPhase("erreur");
        return;
      }
      const creee = await reponse.json();
      setIdentifiant(creee.identifiant);
      setEtape("ingestion");
      setPhase("analyse");
    } catch {
      setErreur(d.analyse.apiIndisponible);
      setPhase("erreur");
    }
  }

  async function supprimer() {
    if (!identifiant) return;
    try {
      await fetch(`${API}/analyses/${identifiant}`, {
        method: "DELETE",
        headers: enteteCode(),
      });
    } catch {
      /* la purge automatique du worker prendra le relais */
    }
    recommencer();
  }

  function recommencer() {
    setFichier(null);
    setIdentifiant(null);
    setRapport(null);
    setErreur("");
    setPhase("depot");
    if (champFichier.current) champFichier.current.value = "";
  }

  /* ——— Code d'accès ——— */
  if (phase === "code") {
    return (
      <div className="mx-auto max-w-md rounded-2xl border border-bord bg-surface p-6">
        <h2 className="text-lg font-bold">{d.analyse.codeTitre}</h2>
        <p className="mt-2 text-sm text-sourdine">{d.analyse.codeTexte}</p>
        <form
          className="mt-4 flex gap-2"
          onSubmit={(evenement) => {
            evenement.preventDefault();
            const valeur = saisieCode.trim();
            if (!valeur) return;
            setCode(valeur);
            try {
              localStorage.setItem(CLE_CODE, valeur);
            } catch {
              /* ignoré */
            }
            setErreur("");
            setPhase("depot");
          }}
        >
          <input
            type="password"
            value={saisieCode}
            onChange={(evenement) => setSaisieCode(evenement.target.value)}
            placeholder={d.analyse.codePlaceholder}
            className="flex-1 rounded-lg border border-bord bg-carte px-3 py-2 text-encre outline-none focus:border-accent"
            autoFocus
          />
          <button
            type="submit"
            className="rounded-lg bg-accent px-4 py-2 font-bold text-fond hover:opacity-90"
          >
            {d.analyse.codeValider}
          </button>
        </form>
        {erreur && <p className="mt-3 text-sm text-[#ff8a8a]">{erreur}</p>}
      </div>
    );
  }

  /* ——— Analyse en cours ——— */
  if (phase === "envoi" || phase === "analyse") {
    const indexCourant = ETAPES.indexOf(etape as (typeof ETAPES)[number]);
    return (
      <div className="mx-auto max-w-md rounded-2xl border border-bord bg-surface p-6">
        <div className="flex items-center gap-3">
          <span
            className="h-4 w-4 animate-spin rounded-full border-2 border-bord border-t-accent motion-reduce:animate-none"
            aria-hidden
          />
          <p className="font-semibold">
            {phase === "envoi"
              ? d.analyse.envoi
              : libelleEtape(etape, locale)}
          </p>
        </div>
        <ol className="mt-5 flex flex-col gap-2">
          {ETAPES.map((cle, index) => {
            const faite = phase === "analyse" && index < indexCourant;
            const active = phase === "analyse" && index === indexCourant;
            return (
              <li
                key={cle}
                className={`flex items-center gap-2.5 text-sm ${
                  active ? "text-encre" : faite ? "text-accent" : "text-faible"
                }`}
              >
                <span aria-hidden>{faite ? "✓" : active ? "▸" : "·"}</span>
                {libelleEtape(cle, locale)}
              </li>
            );
          })}
        </ol>
        <p className="mt-5 text-xs text-faible">{d.analyse.patience}</p>
      </div>
    );
  }

  /* ——— Erreur ——— */
  if (phase === "erreur") {
    return (
      <div className="mx-auto max-w-md rounded-2xl border border-[#3a1519] bg-surface p-6">
        <h2 className="text-lg font-bold text-[#ff8a8a]">
          {d.analyse.erreurTitre}
        </h2>
        <p className="mt-2 text-sm text-sourdine">{erreur}</p>
        <button
          onClick={recommencer}
          className="mt-4 rounded-full bg-accent px-5 py-2 font-bold text-fond hover:opacity-90"
        >
          {d.analyse.reessayer}
        </button>
      </div>
    );
  }

  /* ——— Rapport ——— */
  if (phase === "rapport" && rapport) {
    const urlTimeline = identifiant
      ? `${API}/analyses/${identifiant}/timeline.png?code=${encodeURIComponent(code)}`
      : null;
    return (
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-2xl font-extrabold">{d.rapport.titre}</h1>
          <div className="flex gap-2">
            <button
              onClick={recommencer}
              className="rounded-full bg-accent px-4 py-2 text-sm font-bold text-fond hover:opacity-90"
            >
              {d.analyse.nouvelle}
            </button>
            <button
              onClick={supprimer}
              className="rounded-full border border-bord px-4 py-2 text-sm text-sourdine hover:border-faible hover:text-encre"
            >
              {d.analyse.supprimer}
            </button>
          </div>
        </div>
        <Rapport rapport={rapport} urlTimeline={urlTimeline} d={d} />
      </div>
    );
  }

  /* ——— Dépôt ——— */
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-5">
      <div
        onDragOver={(evenement) => {
          evenement.preventDefault();
          setSurvol(true);
        }}
        onDragLeave={() => setSurvol(false)}
        onDrop={(evenement) => {
          evenement.preventDefault();
          setSurvol(false);
          choisirFichier(evenement.dataTransfer.files);
        }}
        onClick={() => champFichier.current?.click()}
        className={`cursor-pointer rounded-2xl border-2 border-dashed p-8 text-center transition ${
          survol ? "border-accent bg-accent-fonce/30" : "border-bord bg-surface"
        }`}
      >
        <input
          ref={champFichier}
          type="file"
          accept="video/*,.mp4,.mov,.webm,.mkv,.m4v"
          className="hidden"
          onChange={(evenement) => choisirFichier(evenement.target.files)}
        />
        {fichier ? (
          <>
            <p className="text-sm text-faible">{d.analyse.fichierChoisi}</p>
            <p className="mt-1 font-semibold break-all">{fichier.name}</p>
            <p className="mt-2 text-xs text-bleu">{d.analyse.changer}</p>
          </>
        ) : (
          <>
            <p className="text-lg font-semibold">{d.analyse.deposer}</p>
            <p className="mt-2 text-sm text-faible">{d.analyse.deposerAide}</p>
          </>
        )}
      </div>

      <div>
        <h2 className="mb-2 text-sm font-bold uppercase tracking-[0.08em] text-accent">
          {d.analyse.modeTitre}
        </h2>
        <div className="grid gap-3 sm:grid-cols-2">
          {(
            [
              ["ma-video", d.analyse.modeATitre, d.analyse.modeADesc],
              ["inspiration", d.analyse.modeBTitre, d.analyse.modeBDesc],
            ] as const
          ).map(([valeur, titre, description]) => (
            <button
              key={valeur}
              onClick={() => setMode(valeur)}
              aria-pressed={mode === valeur}
              className={`rounded-xl border p-4 text-start transition ${
                mode === valeur
                  ? "border-accent bg-accent-fonce/40"
                  : "border-bord bg-surface hover:border-faible"
              }`}
            >
              <p className="font-bold">{titre}</p>
              <p className="mt-1 text-sm text-sourdine">{description}</p>
            </button>
          ))}
        </div>
      </div>

      <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-bord bg-surface p-4">
        <input
          type="checkbox"
          checked={tiktok}
          onChange={(evenement) => setTiktok(evenement.target.checked)}
          className="mt-1 h-4 w-4 accent-[#5ee6a8]"
        />
        <span>
          <span className="font-semibold">{d.analyse.tiktokTitre}</span>
          <span className="block text-sm text-faible">{d.analyse.tiktokAide}</span>
        </span>
      </label>

      <button
        onClick={lancer}
        disabled={!fichier}
        className="rounded-full bg-accent py-3 text-base font-bold text-fond transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
      >
        {d.analyse.lancer}
      </button>
      {erreur && <p className="text-sm text-[#ff8a8a]">{erreur}</p>}
    </div>
  );
}
