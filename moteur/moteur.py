#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MOTEUR V1 — Analyse & génération pour vidéos courtes (CLI)
==========================================================
Les 5 étages :
  1. Ingestion locale (reference_ingestion — gratuit, sans API)
  2. Transcription Whisper (si OPENAI_API_KEY présente)
  3. Compréhension vidéo native (Gemini — obligatoire)
  4. Décodeur — extraction de la FORMULE (Gemini)
  5. Générateur — mode A (ma-video) ou mode B (inspiration) (Gemini)

Usage :
  python moteur.py video.mp4 --mode ma-video --profil profil.json
  python moteur.py viral.mp4 --mode inspiration --profil profil.json
  # Capture d'écran TikTok (interface à ignorer) :
  python moteur.py capture.mp4 --mode inspiration --profil profil.json \
      --crop-top 0.12 --crop-bottom 0.15

Sorties (dossier sorties/<nom-video>/) :
  rapport.html, rapport.json, timeline_attention.png, transcript.txt
"""
import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
from pathlib import Path

from dotenv import load_dotenv

import prompts
from rapport import generer_rapport_html
from reference_ingestion import ingerer, metadonnees

# ----------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------
DOSSIER_MODULE = Path(__file__).resolve().parent
DUREE_MAX_S = 180  # limite V1 : 3 minutes

# Identifiants de modèles vérifiés dans la documentation officielle
# (août 2026) : gemini-3.6-flash = famille Flash la plus récente, stable
# (GA), accepte la vidéo en entrée. Remplaçables via .env.
MODELE_VIDEO_DEFAUT = "gemini-3.6-flash"
MODELE_TEXTE_DEFAUT = "gemini-3.6-flash"
MODELE_WHISPER = "whisper-1"

# Estimation de coût (tarifs standard gemini-3.6-flash, août 2026, USD
# par million de tokens). La vidéo consomme ~263 tokens/s (visuel) +
# ~32 tokens/s (audio) en résolution média par défaut.
PRIX_ENTREE_PAR_M = 1.50
PRIX_SORTIE_PAR_M = 7.50
TOKENS_VIDEO_PAR_S = 300


# ----------------------------------------------------------------------
# Affichage & erreurs
# ----------------------------------------------------------------------
def etage(numero, titre):
    print(f"\n{'═' * 62}\n  ÉTAGE {numero} — {titre}\n{'═' * 62}")


def erreur_fatale(message):
    print(f"\n✗ ERREUR : {message}", file=sys.stderr)
    sys.exit(1)


def traduire_erreur_api(exc):
    """Erreurs prévisibles → message en français clair, sans stacktrace."""
    texte = str(exc)
    bas = texte.lower()
    if "429" in texte or "quota" in bas or "resource_exhausted" in bas or "rate limit" in bas:
        return ("quota API dépassé ou limite de débit atteinte — attendez un "
                "peu ou vérifiez votre facturation")
    if "401" in texte or "403" in texte or "api key" in bas or "api_key" in bas \
            or "permission" in bas or "unauthenticated" in bas or "invalid_api" in bas:
        return "clé API invalide ou non autorisée — vérifiez votre fichier .env"
    if "timeout" in bas or "deadline" in bas or "timed out" in bas:
        return "délai réseau dépassé — vérifiez votre connexion"
    if "connection" in bas or "unreachable" in bas or "dns" in bas:
        return "connexion impossible au service — vérifiez votre réseau"
    return f"échec de l'appel ({texte[:200]})"


def avec_retry(fonction, nom):
    """try/except + un retry (brief §9). Fatal après le second échec."""
    try:
        return fonction()
    except Exception as e:  # noqa: BLE001 — tout échec API est traduit
        print(f"  ⚠ {nom} : {traduire_erreur_api(e)} — nouvel essai dans 5 s…")
        time.sleep(5)
        try:
            return fonction()
        except Exception as e2:  # noqa: BLE001
            erreur_fatale(f"{nom} : {traduire_erreur_api(e2)}")


def confirmer_cout(duree_video_s, tokens_entree, tokens_sortie, description, oui):
    """Avant chaque appel Gemini : durée de la vidéo + coût estimé (brief §3)."""
    cout = (tokens_entree * PRIX_ENTREE_PAR_M + tokens_sortie * PRIX_SORTIE_PAR_M) / 1e6
    print(f"  Appel Gemini : {description}")
    print(f"  Durée de la vidéo : {duree_video_s:.1f} s")
    print(f"  Coût estimé : ~{cout:.4f} $ "
          f"(≈{int(tokens_entree):,} tokens en entrée, ≈{int(tokens_sortie):,} en sortie) "
          f"— estimation non calibrée".replace(",", " "))
    if oui:
        return
    if not sys.stdin.isatty():
        erreur_fatale("confirmation impossible (pas de terminal interactif). "
                      "Relancez avec --oui pour accepter les coûts estimés.")
    reponse = input("  Continuer ? [o/N] ").strip().lower()
    if reponse not in ("o", "oui", "y", "yes"):
        print("  Appel annulé par l'utilisateur — arrêt du moteur.")
        sys.exit(0)


# ----------------------------------------------------------------------
# JSON : extraction + validation de schéma (brief §9)
# ----------------------------------------------------------------------
def extraire_json(texte):
    if not texte or not texte.strip():
        raise ValueError("réponse vide")
    t = texte.strip()
    t = re.sub(r"^```(?:json)?\s*", "", t)
    t = re.sub(r"\s*```$", "", t)
    positions = [i for i in (t.find("{"), t.find("[")) if i >= 0]
    if not positions:
        raise ValueError("aucun JSON dans la réponse")
    objet, _ = json.JSONDecoder().raw_decode(t[min(positions):])
    return objet


def problemes_dict(donnees, champs):
    """champs = [(cle, types_acceptes)] ; None est toujours accepté."""
    if not isinstance(donnees, dict):
        return ["la racine doit être un objet JSON"]
    pbs = []
    for cle, types in champs:
        if cle not in donnees:
            pbs.append(f"champ manquant : {cle}")
        elif donnees[cle] is not None and not isinstance(donnees[cle], types):
            pbs.append(f"type invalide pour « {cle} »")
    return pbs


SCHEMA_COMPREHENSION = [
    ("resume", str), ("langues_detectees", list), ("texte_a_l_ecran", list),
    ("moments_forts", list), ("emotions_percues", list), ("contact_camera", str),
    ("debit_et_energie", str), ("elements_visuels_cles", list),
    ("qualite_percue", dict),
]
SCHEMA_FORMULE = [
    ("format", str), ("hook", dict), ("structure", list),
    ("declencheur_emotionnel", str), ("declencheur_de_partage", str),
    ("elements_transferables", list), ("elements_lies_au_sujet", list),
    ("faiblesses_observees", list), ("pourquoi_ca_marche", str),
]
SCHEMA_GENERATION_A = [
    ("verdict", str), ("actions_prioritaires", list), ("hooks_reecrits", list),
    ("texte_ecran_2_temps", list), ("cta_fin", str),
]
CHAMPS_FICHE_B = [
    ("titre_hook", str), ("pourquoi_ca_marche", str), ("script_complet", str),
    ("textes_ecran", list), ("plan_de_tournage", list), ("cta", str),
    ("duree_cible_s", (int, float)), ("sensibilite_plateforme", str),
    ("actualite_requise", bool),
]
SCHEMA_CULTURE = [
    ("pays", str), ("sensibilites", dict), ("references_locales", list),
    ("calendrier", list), ("codes_communication", list),
]


def valider_comprehension(o):
    return problemes_dict(o, SCHEMA_COMPREHENSION)


def valider_formule(o):
    pbs = problemes_dict(o, SCHEMA_FORMULE)
    if not pbs and not o["structure"]:
        pbs.append("« structure » ne doit pas être vide")
    return pbs


def valider_generation_a(o):
    pbs = problemes_dict(o, SCHEMA_GENERATION_A)
    if pbs:
        return pbs
    if o["verdict"] not in ("pret_a_publier", "optimiser_avant", "revoir"):
        pbs.append("« verdict » doit être pret_a_publier, optimiser_avant ou revoir")
    if not 1 <= len(o["actions_prioritaires"]) <= 5:
        pbs.append("« actions_prioritaires » doit contenir de 3 à 5 actions")
    return pbs


def valider_generation_b(o):
    if not isinstance(o, list):
        return ["la racine doit être une LISTE de 5 fiches"]
    pbs = []
    if len(o) != 5:
        pbs.append(f"il faut exactement 5 fiches (reçu : {len(o)})")
    for i, fiche in enumerate(o):
        for pb in problemes_dict(fiche, CHAMPS_FICHE_B):
            pbs.append(f"fiche {i + 1} : {pb}")
    return pbs


def valider_culture(o):
    return problemes_dict(o, SCHEMA_CULTURE)


def appel_json(generer_texte, valider, nom):
    """
    generer_texte(correctif) -> texte brut du modèle (correctif ajouté au
    prompt en cas de reprise). JSON invalide ou schéma non respecté →
    re-demander une fois (brief §9), puis erreur fatale.
    """
    correctif = None
    for tentative in (1, 2):
        texte = generer_texte(correctif)
        try:
            objet = extraire_json(texte)
        except (ValueError, json.JSONDecodeError) as e:
            print(f"  ⚠ {nom} : JSON invalide ({e}) — nouvelle demande…")
            correctif = ("\n\nIMPORTANT : ta réponse précédente n'était pas un "
                         "JSON valide. Renvoie UNIQUEMENT le JSON demandé, sans "
                         "texte autour ni bloc de code.")
            continue
        problemes = valider(objet)
        if not problemes:
            return objet
        print(f"  ⚠ {nom} : schéma non respecté ({'; '.join(problemes[:4])}) — nouvelle demande…")
        correctif = ("\n\nIMPORTANT : ton JSON précédent ne respectait pas le "
                     "schéma demandé : " + " ; ".join(problemes) +
                     ". Renvoie UNIQUEMENT le JSON corrigé, complet.")
    erreur_fatale(f"{nom} : le modèle n'a pas produit de JSON conforme après "
                  "une re-demande. Relancez le moteur (ou changez de modèle "
                  "dans .env).")


# ----------------------------------------------------------------------
# Client Gemini
# ----------------------------------------------------------------------
class Gemini:
    def __init__(self, cle_api):
        try:
            from google import genai
            from google.genai import types
        except ImportError:
            erreur_fatale("le paquet « google-genai » n'est pas installé. "
                          "Lancez : pip install -r requirements.txt")
        self._types = types
        self.client = genai.Client(api_key=cle_api)

    def televerser_video(self, chemin):
        print("  Téléversement de la vidéo vers Gemini…")
        fichier = self.client.files.upload(file=str(chemin))
        for _ in range(90):
            etat = getattr(getattr(fichier, "state", None), "name", "ACTIVE")
            if etat == "ACTIVE":
                return fichier
            if etat == "FAILED":
                raise RuntimeError("le traitement du fichier a échoué côté Gemini")
            time.sleep(2)
            fichier = self.client.files.get(name=fichier.name)
        raise RuntimeError("délai dépassé pendant le traitement de la vidéo par Gemini")

    def generer(self, modele, contenus, temperature=0.4):
        config = self._types.GenerateContentConfig(
            response_mime_type="application/json", temperature=temperature)
        reponse = self.client.models.generate_content(
            model=modele, contents=contenus, config=config)
        return reponse.text

    def supprimer_fichier(self, fichier):
        try:
            self.client.files.delete(name=fichier.name)
        except Exception:  # noqa: BLE001 — nettoyage best-effort
            pass


# ----------------------------------------------------------------------
# Étage 2 — Transcription Whisper
# ----------------------------------------------------------------------
def _champ(objet, nom, defaut=None):
    if isinstance(objet, dict):
        return objet.get(nom, defaut)
    return getattr(objet, nom, defaut)


def extraire_audio(chemin_video):
    """Audio mono 16 kHz (wav) pour l'envoi à Whisper (brief §4, étage 2)."""
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    tmp.close()
    r = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-i", str(chemin_video), "-vn", "-ac", "1", "-ar", "16000", tmp.name],
        capture_output=True, text=True)
    if r.returncode != 0:
        os.unlink(tmp.name)
        raise RuntimeError(f"extraction audio impossible : {r.stderr.strip()[:200]}")
    return tmp.name


def debit_par_tranches(mots, segments, duree_s, pas=5.0):
    """Débit de parole (mots/minute) par tranche de 5 s (brief §4, étage 2)."""
    temps = [m["s"] for m in mots]
    if not temps:
        for seg in segments:
            n = max(len(seg["texte"].split()), 1)
            d = max(seg["a_s"] - seg["de_s"], 1e-6)
            temps.extend(seg["de_s"] + d * (i + 0.5) / n for i in range(n))
    tranches, t = [], 0.0
    while t < duree_s:
        fin = min(t + pas, duree_s)
        n = sum(1 for x in temps if t <= x < fin)
        minutes = (fin - t) / 60.0
        tranches.append({"de_s": round(t, 1), "a_s": round(fin, 1),
                         "mots_par_minute": round(n / minutes) if minutes else 0})
        t += pas
    return tranches


def transcrire_whisper(chemin_video, cle_openai, duree_s):
    try:
        from openai import OpenAI
    except ImportError:
        erreur_fatale("le paquet « openai » n'est pas installé. "
                      "Lancez : pip install -r requirements.txt")
    client = OpenAI(api_key=cle_openai)
    chemin_audio = extraire_audio(chemin_video)

    def _appel():
        with open(chemin_audio, "rb") as f:
            return client.audio.transcriptions.create(
                model=MODELE_WHISPER, file=f, response_format="verbose_json",
                timestamp_granularities=["segment", "word"])

    try:
        reponse = avec_retry(_appel, "Transcription Whisper")
    finally:
        if os.path.exists(chemin_audio):
            os.unlink(chemin_audio)

    segments = [{"de_s": round(float(_champ(s, "start", 0.0)), 2),
                 "a_s": round(float(_champ(s, "end", 0.0)), 2),
                 "texte": str(_champ(s, "text", "")).strip()}
                for s in (_champ(reponse, "segments") or [])]
    mots = [{"mot": str(_champ(m, "word", "")).strip(),
             "s": round(float(_champ(m, "start", 0.0)), 2)}
            for m in (_champ(reponse, "words") or [])]
    # Règle darija (brief §4) : le texte est conservé tel quel, aucune
    # « correction » vers l'arabe classique, code-switching intact.
    return {"source": "whisper",
            "texte": str(_champ(reponse, "text", "")).strip(),
            "langue_detectee": _champ(reponse, "language"),
            "segments": segments,
            "mots": mots,
            "debit_par_tranche_5s": debit_par_tranches(mots, segments, duree_s)}


# ----------------------------------------------------------------------
# Étages 3-5 — appels Gemini
# ----------------------------------------------------------------------
def comprehension_video(gemini, modele, fichier_video, duree_s, transcript_deja_fait, oui):
    prompt = prompts.PROMPT_COMPREHENSION
    if transcript_deja_fait:
        prompt += "\n\nLe transcript n'est PAS demandé : mets \"transcript\": null."
        tokens_sortie = 1500
    else:
        prompt += ("\n\nLe transcript EST demandé : remplis le champ "
                   "\"transcript\" avec la transcription complète, dans la ou "
                   "les langues d'origine (darija/français/anglais tels quels).")
        tokens_sortie = 3000
    tokens_entree = int(duree_s * TOKENS_VIDEO_PAR_S) + len(prompt) // 4
    confirmer_cout(duree_s, tokens_entree, tokens_sortie,
                   "compréhension vidéo (étage 3)", oui)

    def generer_texte(correctif):
        contenu = prompt + (correctif or "")
        return avec_retry(lambda: gemini.generer(modele, [fichier_video, contenu],
                                                 temperature=0.2),
                          "Compréhension vidéo (Gemini)")

    return appel_json(generer_texte, valider_comprehension, "Étage 3")


def appel_texte_json(gemini, modele, prompt_complet, valider, nom, duree_s,
                     tokens_sortie, description, oui, temperature=0.6):
    tokens_entree = len(prompt_complet) // 4
    confirmer_cout(duree_s, tokens_entree, tokens_sortie, description, oui)

    def generer_texte(correctif):
        contenu = prompt_complet + (correctif or "")
        return avec_retry(lambda: gemini.generer(modele, contenu, temperature=temperature),
                          f"{nom} (Gemini)")

    return appel_json(generer_texte, valider, nom)


# ----------------------------------------------------------------------
# Profil culturel du pays cible (brief §5) — cache data/cultures/
# ----------------------------------------------------------------------
def slug(texte):
    t = unicodedata.normalize("NFKD", texte).encode("ascii", "ignore").decode()
    t = re.sub(r"[^a-zA-Z0-9]+", "-", t).strip("-").lower()
    return t or "pays"


def profil_culturel(gemini, modele, pays, duree_s, oui):
    dossier = DOSSIER_MODULE / "data" / "cultures"
    dossier.mkdir(parents=True, exist_ok=True)
    cache = dossier / f"{slug(pays)}.json"
    if cache.exists():
        print(f"  Profil culturel « {pays} » chargé depuis le cache "
              f"({cache.relative_to(DOSSIER_MODULE)}).")
        try:
            return json.loads(cache.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            print("  ⚠ Cache illisible — régénération.")
    prompt = prompts.PROMPT_CULTURE.replace("{PAYS}", pays)
    culture = appel_texte_json(
        gemini, modele, prompt, valider_culture,
        f"Profil culturel ({pays})", duree_s, 900,
        f"profil culturel du pays cible « {pays} » (premier usage, mis en cache)",
        oui, temperature=0.3)
    cache.write_text(json.dumps(culture, ensure_ascii=False, indent=1),
                     encoding="utf-8")
    print(f"  Profil culturel mis en cache : {cache.relative_to(DOSSIER_MODULE)}")
    return culture


# ----------------------------------------------------------------------
# Orchestration
# ----------------------------------------------------------------------
def bloc_donnees(titre, objet):
    return f"\n\n### {titre} ###\n" + json.dumps(objet, ensure_ascii=False, indent=1)


def ecrire_transcript(chemin, transcription, comprehension):
    lignes = []
    if transcription and transcription.get("segments"):
        for s in transcription["segments"]:
            lignes.append(f"[{s['de_s']:7.2f} → {s['a_s']:7.2f}]  {s['texte']}")
    elif transcription and transcription.get("texte"):
        lignes.append(transcription["texte"])
    elif comprehension.get("transcript"):
        lignes.append(str(comprehension["transcript"]))
    else:
        lignes.append("(aucun transcript disponible — vidéo sans parole ?)")
    chemin.write_text("\n".join(lignes) + "\n", encoding="utf-8")


def main():
    parseur = argparse.ArgumentParser(
        description="MOTEUR V1 — analyse une vidéo courte et génère un rapport "
                    "complet (diagnostic ou fiches idées).")
    parseur.add_argument("video", help="chemin de la vidéo (mp4, mov…)")
    parseur.add_argument("--mode", required=True,
                         choices=["ma-video", "inspiration"],
                         help="ma-video : diagnostic + corrections ; "
                              "inspiration : formule + 5 fiches idées")
    parseur.add_argument("--profil", required=True,
                         help="chemin du profil créateur (profil.json)")
    parseur.add_argument("--crop-top", type=float, default=0.0,
                         help="proportion de l'image à ignorer en haut "
                              "(0.12 pour une capture TikTok ; défaut 0)")
    parseur.add_argument("--crop-bottom", type=float, default=0.0,
                         help="proportion de l'image à ignorer en bas "
                              "(0.15 pour une capture TikTok ; défaut 0)")
    parseur.add_argument("--oui", action="store_true",
                         help="ne pas demander de confirmation avant les appels Gemini")
    args = parseur.parse_args()

    load_dotenv(DOSSIER_MODULE / ".env")
    load_dotenv()  # .env du dossier courant, le cas échéant

    # ---- Vérifications préalables -----------------------------------
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        erreur_fatale("ffmpeg/ffprobe introuvables. Installez ffmpeg "
                      "(ex. « sudo apt install ffmpeg » ou « brew install ffmpeg »).")
    cle_google = (os.environ.get("GOOGLE_API_KEY") or "").strip()
    if not cle_google:
        erreur_fatale("GOOGLE_API_KEY manquante (obligatoire). Copiez "
                      ".env.example vers .env puis renseignez votre clé Gemini.")
    cle_openai = (os.environ.get("OPENAI_API_KEY") or "").strip()
    modele_video = (os.environ.get("GEMINI_MODELE_VIDEO") or MODELE_VIDEO_DEFAUT).strip()
    modele_texte = (os.environ.get("GEMINI_MODELE_TEXTE") or MODELE_TEXTE_DEFAUT).strip()

    chemin_video = Path(args.video)
    if not chemin_video.exists():
        erreur_fatale(f"fichier vidéo introuvable : {chemin_video}")
    chemin_profil = Path(args.profil)
    if not chemin_profil.exists():
        erreur_fatale(f"fichier profil introuvable : {chemin_profil}")
    try:
        profil = json.loads(chemin_profil.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        erreur_fatale(f"profil.json invalide : {e}")

    try:
        meta = metadonnees(str(chemin_video))
    except Exception:  # noqa: BLE001
        erreur_fatale(f"impossible de lire la vidéo « {chemin_video} » — "
                      "fichier corrompu ou format non supporté ?")
    duree_s = meta["duree_s"]
    if duree_s > DUREE_MAX_S:
        erreur_fatale(f"vidéo trop longue : {duree_s:.0f} s. La V1 accepte "
                      f"au maximum {DUREE_MAX_S} s (3 minutes). Coupez la "
                      "vidéo ou choisissez-en une plus courte.")

    dossier_sortie = DOSSIER_MODULE / "sorties" / chemin_video.stem
    dossier_sortie.mkdir(parents=True, exist_ok=True)
    chemin_png = dossier_sortie / "timeline_attention.png"

    print(f"\nMOTEUR V1 — mode « {args.mode} »")
    print(f"Vidéo : {chemin_video.name} ({duree_s:.1f} s, "
          f"{meta['largeur']}×{meta['hauteur']}, {meta['fps']} i/s)")
    print(f"Sorties : {dossier_sortie}")

    # ---- Étage 1 — Ingestion locale ----------------------------------
    etage(1, "Ingestion locale (sans API)")
    ingestion = ingerer(str(chemin_video), args.crop_top, args.crop_bottom,
                        out_json=str(dossier_sortie / "ingestion.json"),
                        out_png=str(chemin_png))
    print(f"  ✓ {len(ingestion['cuts_s'])} cut(s), "
          f"{len(ingestion['zones_plates'])} zone(s) plate(s), "
          f"visage présent {ingestion['visage_present_pct']} % du temps.")
    ingestion_compacte = {k: v for k, v in ingestion.items() if k != "timeline"}

    # ---- Étage 2 — Transcription Whisper (optionnelle) ---------------
    etage(2, "Transcription (Whisper)")
    transcription = None
    if not cle_openai:
        print("  OPENAI_API_KEY absente → le transcript sera demandé à Gemini (étage 3).")
    elif not meta["a_de_l_audio"]:
        print("  La vidéo n'a pas de piste audio → transcription ignorée.")
    else:
        transcription = transcrire_whisper(chemin_video, cle_openai, duree_s)
        print(f"  ✓ {len(transcription['segments'])} segment(s), "
              f"langue détectée : {transcription['langue_detectee'] or '?'} "
              "(texte conservé tel quel, darija non « corrigée »).")

    # ---- Étage 3 — Compréhension vidéo (Gemini) -----------------------
    etage(3, "Compréhension vidéo (Gemini)")
    gemini = Gemini(cle_google)
    fichier_gemini = avec_retry(lambda: gemini.televerser_video(chemin_video),
                                "Téléversement Gemini")
    try:
        comprehension = comprehension_video(
            gemini, modele_video, fichier_gemini, duree_s,
            transcript_deja_fait=transcription is not None, oui=args.oui)
    finally:
        gemini.supprimer_fichier(fichier_gemini)
    print(f"  ✓ Résumé : {str(comprehension.get('resume', ''))[:100]}…")

    chemin_transcript = dossier_sortie / "transcript.txt"
    ecrire_transcript(chemin_transcript, transcription, comprehension)

    # ---- Étage 4 — Le Décodeur (formule) ------------------------------
    etage(4, "Décodeur — extraction de la FORMULE (Gemini)")
    transcript_pour_prompt = (transcription["segments"] if transcription
                              else comprehension.get("transcript"))
    prompt_decodeur = (
        prompts.PROMPT_DECODEUR
        + bloc_donnees("TRANSCRIPT HORODATÉ", transcript_pour_prompt)
        + (bloc_donnees("DÉBIT DE PAROLE (mots/minute par tranche de 5 s)",
                        transcription["debit_par_tranche_5s"]) if transcription else "")
        + bloc_donnees("ANALYSE VISUELLE ET AUDIO (Gemini)", comprehension)
        + bloc_donnees("TIMELINE TECHNIQUE (ingestion locale : loudness, "
                       "silences, cuts, zones plates, pics)", ingestion_compacte))
    formule = appel_texte_json(gemini, modele_texte, prompt_decodeur,
                               valider_formule, "Étage 4", duree_s, 1500,
                               "décodage de la formule (étage 4)", args.oui)
    print(f"  ✓ Format : {formule.get('format', '?')} | "
          f"déclencheur : {formule.get('declencheur_emotionnel', '?')}")

    # ---- Étage 5 — Le Générateur --------------------------------------
    etage(5, "Générateur — " + ("diagnostic (mode A)" if args.mode == "ma-video"
                                else "5 fiches idées (mode B)"))
    pays_cible = (profil.get("pays_cibles") or [profil.get("pays") or "Maroc"])[0]
    culture = profil_culturel(gemini, modele_texte, pays_cible, duree_s, args.oui)

    if args.mode == "ma-video":
        prompt_generateur = (
            prompts.PROMPT_GENERATEUR_A
            + bloc_donnees("FORMULE", formule)
            + bloc_donnees("DONNÉES D'ANALYSE DE MA VIDÉO",
                           {"comprehension": comprehension,
                            "ingestion": ingestion_compacte,
                            "transcript": transcript_pour_prompt})
            + bloc_donnees("PROFIL CRÉATEUR", profil)
            + bloc_donnees("PROFIL CULTUREL DU PAYS CIBLE", culture))
        generation = appel_texte_json(
            gemini, modele_texte, prompt_generateur, valider_generation_a,
            "Étage 5 (mode A)", duree_s, 1500,
            "diagnostic + hooks réécrits (étage 5, mode A)", args.oui)
        print(f"  ✓ Verdict : {generation['verdict']} — "
              f"{len(generation['actions_prioritaires'])} action(s) prioritaire(s).")
    else:
        prompt_generateur = (
            prompts.PROMPT_GENERATEUR_B
            + bloc_donnees("FORMULE (extraite de la vidéo virale)", formule)
            + bloc_donnees("PROFIL CRÉATEUR", profil)
            + bloc_donnees("PROFIL CULTUREL DU PAYS CIBLE", culture))
        generation = appel_texte_json(
            gemini, modele_texte, prompt_generateur, valider_generation_b,
            "Étage 5 (mode B)", duree_s, 3500,
            "génération des 5 fiches idées (étage 5, mode B)", args.oui)
        print(f"  ✓ {len(generation)} fiches idées générées.")

    # ---- Rapport -------------------------------------------------------
    etage("★", "Rapport")
    rapport = {
        "video": chemin_video.name,
        "mode": args.mode,
        "genere_le": datetime.datetime.now().isoformat(timespec="seconds"),
        "modeles": {"video": modele_video, "texte": modele_texte,
                    "transcription": MODELE_WHISPER if transcription else None},
        "profil": profil,
        "profil_culturel": culture,
        "ingestion": ingestion,
        "transcription": transcription,
        "comprehension": comprehension,
        "formule": formule,
        "generation": generation,
    }
    chemin_json = dossier_sortie / "rapport.json"
    chemin_json.write_text(json.dumps(rapport, ensure_ascii=False, indent=1),
                           encoding="utf-8")
    chemin_html = dossier_sortie / "rapport.html"
    generer_rapport_html(rapport, chemin_png, chemin_html)

    print("\n✓ Terminé. Fichiers produits :")
    for f in ("rapport.html", "rapport.json", "timeline_attention.png",
              "transcript.txt"):
        print(f"   {dossier_sortie / f}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nInterrompu par l'utilisateur.")
        sys.exit(130)
