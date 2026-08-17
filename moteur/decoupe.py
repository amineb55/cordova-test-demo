#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MOTEUR DÉCOUPE V1 — vidéo longue → shorts prêts à publier
=========================================================
Prend une vidéo longue (jusqu'à 90 min) et produit N shorts 9:16
sous-titrés, habillés, classés par potentiel. Réutilise les briques du
moteur existant (reference_ingestion, client Gemini, profil culturel,
charte de rapport) sans toucher au comportement de moteur.py.

Les 7 étapes :
  1. Ingestion adaptée (pas adaptatif, signaux audio : pics, zones plates, cuts)
  2. Transcription complète (Whisper mots+segments ; repli Gemini audio)
  3. Sélection des moments candidats (PROMPT_SELECTION, texte seul)
  4. Scores et classement (PROMPT_SCORING)
  5. Découpe et recadrage 9:16 (FFmpeg + OpenCV, visage|centre|flou)
  6. Sous-titres incrustés (ASS mot à mot, RTL géré, hook en haut)
  7. Habillage (PROMPT_HABILLAGE : titre, hook, caption, hashtags)

Usage :
  python decoupe.py longue.mp4 --profil profil.json --nb-shorts 8 --oui
"""
import argparse
import datetime
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np
from dotenv import load_dotenv

import prompts
import reference_ingestion as ri
from moteur import (DOSSIER_MODULE, MODELE_TEXTE_DEFAUT, PRIX_ENTREE_PAR_M,
                    PRIX_SORTIE_PAR_M, SCHEMA_PROFIL, THINKING_BUDGET_DEFAUT,
                    Gemini, appel_json, appel_texte_json, avec_retry,
                    bloc_donnees, confirmer_cout, erreur_fatale, etage,
                    fmt_tokens, premier_pays_cible, problemes_dict,
                    profil_culturel)
from rapport_decoupe import generer_rapport_decoupe_html

# ----------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------
DUREE_MAX_S = 90 * 60          # limite V1 : 90 minutes
SEUIL_PAS_ADAPTATIF_S = 15 * 60  # au-delà de 15 min : STEP 0,5 s
COUT_WHISPER_PAR_MIN = 0.006   # USD (tarif publié, août 2026)
DUREE_TRONCON_WHISPER_S = 600  # tronçons envoyés à Whisper (limite 25 Mo)
MODELE_WHISPER = "whisper-1"
LARGEUR_SORTIE, HAUTEUR_SORTIE = 1080, 1920
PAD_COUPE_S = 0.2
COULEUR_ACCENT_ASS = "&H00A8E65E"   # #5ee6a8 (charte) en BGR ASS
COULEUR_BLANC_ASS = "&H00FFFFFF"


# ----------------------------------------------------------------------
# Outils ffmpeg
# ----------------------------------------------------------------------
def executer_ffmpeg(arguments, nom, cwd=None):
    r = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        *[str(a) for a in arguments]],
                       capture_output=True, text=True, cwd=cwd)
    if r.returncode != 0:
        raise RuntimeError(f"{nom} : ffmpeg a échoué "
                           f"({r.stderr.strip()[-300:] or 'sans message'})")


def extraire_audio_mp3(video, sortie, de_s=None, duree_s=None):
    args = []
    if de_s is not None:
        args += ["-ss", f"{de_s:.3f}"]
    args += ["-i", video]
    if duree_s is not None:
        args += ["-t", f"{duree_s:.3f}"]
    args += ["-vn", "-ac", "1", "-ar", "16000", "-b:a", "48k", sortie]
    executer_ffmpeg(args, "extraction audio")


# ----------------------------------------------------------------------
# Étape 1 — Ingestion adaptée (signaux audio, pas adaptatif)
# ----------------------------------------------------------------------
def ingestion_adaptee(video, duree_s):
    if duree_s > SEUIL_PAS_ADAPTATIF_S:
        ri.STEP = 0.5  # pas adaptatif (brief §4.1) — paramétré, pas dupliqué
        print(f"  Pas d'analyse adaptatif : {ri.STEP} s (vidéo > 15 min).")
    at, adb = ri.energie_audio(video)
    # énergie audio normalisée 0-100 → pics d'énergie et zones plates
    norme = np.clip((np.array(adb) + 50.0) * 2.0, 0, 100)
    zones, pics, _ = ri.zones_et_pics(np.array(at), norme)
    silences = ri.silences(video)
    cuts = ri.cuts(video)
    print(f"  ✓ {len(pics)} pic(s) d'énergie, {len(zones)} zone(s) plate(s), "
          f"{len(silences)} silence(s), {len(cuts)} cut(s).")
    return {"pas_s": ri.STEP, "pics_energie_s": pics, "zones_plates": zones,
            "silences": silences, "cuts_s": cuts}


# ----------------------------------------------------------------------
# Étape 2 — Transcription complète
# ----------------------------------------------------------------------
def _champ(objet, nom, defaut=None):
    if isinstance(objet, dict):
        return objet.get(nom, defaut)
    return getattr(objet, nom, defaut)


def bornes_troncons(duree_s, silences):
    """Frontières de tronçons Whisper, collées aux silences (±30 s)."""
    bornes, cible = [0.0], DUREE_TRONCON_WHISPER_S
    while cible < duree_s:
        proches = [((s["de_s"] + s["a_s"]) / 2)
                   for s in silences
                   if abs((s["de_s"] + s["a_s"]) / 2 - cible) <= 30]
        bornes.append(min(proches, key=lambda x: abs(x - cible))
                      if proches else float(cible))
        cible += DUREE_TRONCON_WHISPER_S
    bornes.append(float(duree_s))
    return bornes


def confirmer_cout_whisper(duree_s, oui):
    minutes = duree_s / 60.0
    cout = minutes * COUT_WHISPER_PAR_MIN
    print(f"  Appel Whisper : transcription complète ({minutes:.1f} min d'audio)")
    print(f"  Coût estimé : ~{cout:.4f} $ ({COUT_WHISPER_PAR_MIN} $/min) "
          "— estimation non calibrée")
    if oui:
        return
    if not sys.stdin.isatty():
        erreur_fatale("confirmation impossible (pas de terminal interactif). "
                      "Relancez avec --oui pour accepter les coûts estimés.")
    if input("  Continuer ? [o/N] ").strip().lower() not in ("o", "oui", "y", "yes"):
        print("  Appel annulé par l'utilisateur — arrêt.")
        sys.exit(0)


def transcrire_whisper_longue(video, cle_openai, duree_s, silences, oui):
    try:
        from openai import OpenAI
    except ImportError:
        erreur_fatale("le paquet « openai » n'est pas installé. "
                      "Lancez : pip install -r requirements.txt")
    confirmer_cout_whisper(duree_s, oui)
    client = OpenAI(api_key=cle_openai)
    bornes = bornes_troncons(duree_s, silences)
    segments, mots = [], []
    for i in range(len(bornes) - 1):
        de, a = bornes[i], bornes[i + 1]
        if a - de < 0.5:
            continue
        if len(bornes) > 2:
            print(f"  Tronçon {i + 1}/{len(bornes) - 1} "
                  f"({de / 60:.1f} → {a / 60:.1f} min)…")
        tmp = tempfile.NamedTemporaryFile(suffix=".mp3", delete=False)
        tmp.close()
        try:
            extraire_audio_mp3(video, tmp.name, de_s=de, duree_s=a - de)

            def _appel():
                with open(tmp.name, "rb") as f:
                    return client.audio.transcriptions.create(
                        model=MODELE_WHISPER, file=f,
                        response_format="verbose_json",
                        timestamp_granularities=["segment", "word"])

            rep = avec_retry(_appel, f"Transcription Whisper (tronçon {i + 1})")
        finally:
            os.unlink(tmp.name)
        for s in (_champ(rep, "segments") or []):
            segments.append({"de_s": round(float(_champ(s, "start", 0)) + de, 2),
                             "a_s": round(float(_champ(s, "end", 0)) + de, 2),
                             "texte": str(_champ(s, "text", "")).strip()})
        for m in (_champ(rep, "words") or []):
            mots.append({"mot": str(_champ(m, "word", "")).strip(),
                         "de_s": round(float(_champ(m, "start", 0)) + de, 2),
                         "a_s": round(float(_champ(m, "end", 0)) + de, 2)})
    # règle darija (brief) : texte conservé tel quel, aucun post-traitement
    return {"source": "whisper", "segments": segments, "mots": mots}


def valider_segments_audio(o):
    if not isinstance(o, list) or not o:
        return ["une liste non vide de segments est attendue"]
    pbs = []
    for i, s in enumerate(o[:400]):
        for pb in problemes_dict(s, [("de_s", (int, float)),
                                     ("a_s", (int, float)), ("texte", str)]):
            pbs.append(f"segment {i} : {pb}")
    return pbs[:6]


def transcrire_gemini_audio(gemini, modele, video, duree_s, oui):
    print("  ⚠ OPENAI_API_KEY absente : transcription par Gemini (audio seul), "
          "granularité SEGMENT — les sous-titres seront par groupes de mots, "
          "moins précis que le mot à mot Whisper.")
    tokens_entree = int(duree_s * 32) + len(prompts.PROMPT_TRANSCRIPTION_AUDIO) // 4
    confirmer_cout(duree_s, tokens_entree, int(duree_s * 4) + 200,
                   gemini.thinking_budget, "transcription audio (repli sans Whisper)", oui)
    tmp = tempfile.NamedTemporaryFile(suffix=".mp3", delete=False)
    tmp.close()
    try:
        extraire_audio_mp3(video, tmp.name)
        fichier = avec_retry(lambda: gemini.televerser_video(tmp.name),
                             "Téléversement audio Gemini")
    finally:
        os.unlink(tmp.name)
    try:
        def generer_texte(correctif):
            contenu = prompts.PROMPT_TRANSCRIPTION_AUDIO + (correctif or "")
            return avec_retry(lambda: gemini.generer(modele, [fichier, contenu],
                                                     temperature=0.1),
                              "Transcription audio (Gemini)")

        segments = appel_json(generer_texte, valider_segments_audio,
                              "Transcription audio")
    finally:
        gemini.supprimer_fichier(fichier)
    segments = [{"de_s": round(float(s["de_s"]), 2),
                 "a_s": round(float(s["a_s"]), 2),
                 "texte": s["texte"].strip()}
                for s in segments if s["a_s"] > s["de_s"] and s["texte"].strip()]
    return {"source": "gemini-audio", "segments": segments, "mots": None}


# ----------------------------------------------------------------------
# Étape 3 — Sélection des candidats
# ----------------------------------------------------------------------
def transcript_horodate(segments):
    return "\n".join(f"[{s['de_s']:.1f}-{s['a_s']:.1f}] {s['texte']}"
                     for s in segments)


def valider_selection(o):
    if not isinstance(o, list) or not o:
        return ["une liste non vide de candidats est attendue"]
    pbs = []
    for i, c in enumerate(o):
        for pb in problemes_dict(c, [("de_s", (int, float)), ("a_s", (int, float)),
                                     ("titre_travail", str), ("type", str),
                                     ("resume", str), ("autonome", bool),
                                     ("raison", str)]):
            pbs.append(f"candidat {i} : {pb}")
    return pbs[:8]


def ajuster_aux_phrases(candidats, segments, duree_s, dmin, dmax):
    """Colle les bornes aux frontières de phrases (jamais couper un mot) et
    valide : de_s < a_s, dans la durée, durée dans les bornes (brief §7)."""
    debuts = [s["de_s"] for s in segments]
    fins = [s["a_s"] for s in segments]
    valides, rejetes = [], []
    for c in candidats:
        de, a = float(c["de_s"]), float(c["a_s"])
        if not (0 <= de < a <= duree_s + 1):
            rejetes.append({**c, "raison_rejet":
                            f"bornes invalides ({de:.1f} → {a:.1f} s)"})
            continue
        de_aj = min(debuts, key=lambda x: abs(x - de)) if debuts else de
        a_aj = min(fins, key=lambda x: abs(x - a)) if fins else a
        de_aj, a_aj = max(de_aj, 0.0), min(a_aj, duree_s)
        duree_c = a_aj - de_aj
        if duree_c < dmin * 0.5:
            rejetes.append({**c, "raison_rejet":
                            f"trop court après ajustement ({duree_c:.1f} s)"})
            continue
        if duree_c > dmax * 1.3:
            rejetes.append({**c, "raison_rejet":
                            f"trop long après ajustement ({duree_c:.1f} s)"})
            continue
        valides.append({**c, "de_s": round(de_aj, 2), "a_s": round(a_aj, 2),
                        "duree_s": round(duree_c, 1)})
    return valides, rejetes


def dedupliquer(retenus):
    """Écarte les chevauchements > 50 % (le mieux noté gagne)."""
    gardes, ecartes = [], []
    for c in retenus:  # déjà triés par score décroissant
        recouvre = False
        for g in gardes:
            inter = min(c["a_s"], g["a_s"]) - max(c["de_s"], g["de_s"])
            if inter > 0.5 * (c["a_s"] - c["de_s"]):
                ecartes.append({**c, "raison_rejet":
                                f"chevauche « {g['titre_travail']} » (mieux noté)"})
                recouvre = True
                break
        if not recouvre:
            gardes.append(c)
    return gardes, ecartes


# ----------------------------------------------------------------------
# Étape 4 — Scoring
# ----------------------------------------------------------------------
def valider_scoring(nombre):
    def _v(o):
        if not isinstance(o, list) or not o:
            return ["une liste non vide de notes est attendue"]
        pbs = []
        for i, n in enumerate(o):
            for pb in problemes_dict(n, [("index", int), ("score", (int, float)),
                                         ("justification", str), ("risque", str)]):
                pbs.append(f"note {i} : {pb}")
            if not pbs and not 0 <= n["index"] < nombre:
                pbs.append(f"note {i} : index hors limites")
            if not pbs and not 0 <= n["score"] <= 100:
                pbs.append(f"note {i} : score hors de 0-100")
        return pbs[:8]
    return _v


# ----------------------------------------------------------------------
# Étape 5 — Découpe et recadrage
# ----------------------------------------------------------------------
def extraire_clip(video, de_s, a_s, sortie):
    executer_ffmpeg(["-ss", f"{de_s:.3f}", "-i", video, "-t", f"{a_s - de_s:.3f}",
                     "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                     "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
                     "-pix_fmt", "yuv420p", sortie], "extraction du clip")


def geometrie_crop(largeur, hauteur):
    """Fenêtre 9:16 maximale dans la source (paire pour l'encodeur)."""
    cw = min(largeur, int(hauteur * 9 / 16))
    ch = min(hauteur, int(largeur * 16 / 9))
    return cw - cw % 2, ch - ch % 2


def trajectoire_visage(clip):
    """Centre X du visage échantillonné 1×/s, lissé par moyenne glissante.
    None si détection trop peu fiable (< 30 % des échantillons)."""
    cap = cv2.VideoCapture(str(clip))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    duree = (cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0) / fps
    frontal = cv2.CascadeClassifier(
        cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    profil_c = cv2.CascadeClassifier(
        cv2.data.haarcascades + "haarcascade_profileface.xml")
    l_reduit = 480
    ts, cxs, total = [], [], 0
    t = 0.0
    while t < max(duree, 0.5):
        cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
        ok, frame = cap.read()
        if not ok:
            break
        total += 1
        g = cv2.cvtColor(cv2.resize(frame, (l_reduit, int(l_reduit * h / w))),
                         cv2.COLOR_BGR2GRAY)
        faces = frontal.detectMultiScale(g, 1.1, 4)
        if len(faces) == 0:
            faces = profil_c.detectMultiScale(g, 1.1, 4)
        if len(faces):
            x, _, fw, _ = max(faces, key=lambda r: r[2] * r[3])
            ts.append(t)
            cxs.append((x + fw / 2) * (w / l_reduit))
        t += 1.0
    cap.release()
    if total == 0 or len(ts) / total < 0.3:
        return None
    cw, _ = geometrie_crop(w, h)
    grille = np.arange(0.0, max(duree, 1.0), 1.0)
    cx = np.interp(grille, ts, cxs)
    noyau = np.ones(5)
    lisse = np.convolve(cx, noyau, "same") / np.convolve(np.ones_like(cx), noyau, "same")
    return {"t": grille, "cx": np.clip(lisse, cw / 2, w - cw / 2)}


def recadrer_visage_opencv(clip, traj, sortie_muette):
    """Recadrage 9:16 dynamique suivant la trajectoire lissée (vidéo seule)."""
    cap = cv2.VideoCapture(str(clip))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    cw, ch = geometrie_crop(w, h)
    y0 = (h - ch) // 2
    sortie = cv2.VideoWriter(str(sortie_muette),
                             cv2.VideoWriter_fourcc(*"mp4v"), fps, (cw, ch))
    i = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        cx = float(np.interp(i / fps, traj["t"], traj["cx"]))
        x0 = int(round(min(max(cx - cw / 2, 0), w - cw)))
        sortie.write(frame[y0:y0 + ch, x0:x0 + cw])
        i += 1
    cap.release()
    sortie.release()
    if i == 0:
        raise RuntimeError("recadrage visage : aucune image lue")


# ----------------------------------------------------------------------
# Étape 6 — Sous-titres ASS (mot à mot, RTL géré)
# ----------------------------------------------------------------------
def est_rtl(texte):
    for c in texte:
        o = ord(c)
        if 0x0590 <= o <= 0x08FF or 0xFB1D <= o <= 0xFEFC:
            return True
        if c.isalpha() and o < 0x0590:
            return False
    return False


def nettoyer_ass(texte):
    return str(texte).replace("{", "(").replace("}", ")") \
                     .replace("\\", "/").replace("\n", " ").strip()


def ts_ass(t):
    t = max(float(t), 0.0)
    return f"{int(t // 3600)}:{int(t % 3600 // 60):02d}:{t % 60:05.2f}"


def mots_depuis_segments(segments):
    """Pseudo-mots à temps interpolés quand Whisper est absent (repli)."""
    mots = []
    for s in segments:
        morceaux = s["texte"].split()
        if not morceaux:
            continue
        duree = max(s["a_s"] - s["de_s"], 0.2)
        pas = duree / len(morceaux)
        for i, m in enumerate(morceaux):
            mots.append({"mot": m,
                         "de_s": round(s["de_s"] + i * pas, 2),
                         "a_s": round(s["de_s"] + (i + 1) * pas, 2)})
    return mots


def grouper_mots(mots, max_mots=3, ecart_max=0.6):
    groupes, courant = [], []
    for m in mots:
        if courant and (len(courant) >= max_mots
                        or m["de_s"] - courant[-1]["a_s"] > ecart_max):
            groupes.append(courant)
            courant = []
        courant.append(m)
    if courant:
        groupes.append(courant)
    return groupes


def generer_ass(chemin, mots_clip, hook, duree_clip):
    """Groupes de 1-3 mots, gros caractères centrés en bas, mot actif en
    couleur d'accent (karaoké \\k ; pour le RTL, groupe entier en accent
    pour un rendu bidi fiable). Hook optionnel en haut (3,5 premières s)."""
    texte_complet = " ".join(m["mot"] for m in mots_clip)
    rtl = est_rtl(texte_complet or (hook or ""))
    police = "Noto Sans Arabic" if rtl else "DejaVu Sans"
    lignes = [f"""[Script Info]
ScriptType: v4.00+
PlayResX: {LARGEUR_SORTIE}
PlayResY: {HAUTEUR_SORTIE}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Sous,{police},96,{COULEUR_ACCENT_ASS},{COULEUR_BLANC_ASS},&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,5,2,2,60,60,340,1
Style: Hook,{police},72,{COULEUR_BLANC_ASS},{COULEUR_BLANC_ASS},&H00000000,&H90000000,-1,0,0,0,100,100,0,0,3,6,0,8,60,60,180,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"""]
    if hook:
        lignes.append(f"Dialogue: 1,{ts_ass(0)},{ts_ass(min(3.5, duree_clip))},"
                      f"Hook,,0,0,0,,{nettoyer_ass(hook)}")
    for groupe in grouper_mots(mots_clip):
        deb = groupe[0]["de_s"]
        fin = max(groupe[-1]["a_s"], deb + 0.35)
        if rtl:
            texte = nettoyer_ass(" ".join(m["mot"] for m in groupe))
        else:
            morceaux = []
            for m in groupe:
                cs = max(int(round((m["a_s"] - m["de_s"]) * 100)), 8)
                morceaux.append(f"{{\\k{cs}}}{nettoyer_ass(m['mot'])}")
            texte = " ".join(morceaux)
        lignes.append(f"Dialogue: 0,{ts_ass(deb)},{ts_ass(min(fin, duree_clip))},"
                      f"Sous,,0,0,0,,{texte}")
    Path(chemin).write_text("\n".join(lignes) + "\n", encoding="utf-8")
    return rtl


# ----------------------------------------------------------------------
# Assemblage final d'un short
# ----------------------------------------------------------------------
def assembler_short(dossier_travail, clip, video_recadree, mode, avec_ass, sortie):
    """Encodage final : recadrage/scale + sous-titres + loudness -14 LUFS."""
    audio_norme = "loudnorm=I=-14:TP=-1.5:LRA=11"
    ass = ",ass=sous_titres.ass" if avec_ass else ""
    commun = ["-c:v", "libx264", "-crf", "19", "-preset", "medium",
              "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
              "-pix_fmt", "yuv420p", "-movflags", "+faststart", sortie]
    if video_recadree is not None:                     # mode visage
        executer_ffmpeg(
            ["-i", video_recadree, "-i", clip, "-map", "0:v", "-map", "1:a",
             "-vf", f"scale={LARGEUR_SORTIE}:{HAUTEUR_SORTIE},setsar=1{ass}",
             "-af", audio_norme, *commun],
            "assemblage (visage)", cwd=dossier_travail)
    elif mode == "flou":
        executer_ffmpeg(
            ["-i", clip, "-filter_complex",
             f"[0:v]split[a][b];"
             f"[a]scale={LARGEUR_SORTIE}:{HAUTEUR_SORTIE}:"
             f"force_original_aspect_ratio=increase,"
             f"crop={LARGEUR_SORTIE}:{HAUTEUR_SORTIE},gblur=sigma=30[fond];"
             f"[b]scale={LARGEUR_SORTIE}:{HAUTEUR_SORTIE}:"
             f"force_original_aspect_ratio=decrease:force_divisible_by=2[avant];"
             f"[fond][avant]overlay=(W-w)/2:(H-h)/2,setsar=1{ass}[v]",
             "-map", "[v]", "-map", "0:a", "-af", audio_norme, *commun],
            "assemblage (flou)", cwd=dossier_travail)
    else:                                              # mode centre
        executer_ffmpeg(
            ["-i", clip, "-vf",
             f"crop='min(iw,ih*9/16)':'min(ih,iw*16/9)',"
             f"scale={LARGEUR_SORTIE}:{HAUTEUR_SORTIE},setsar=1{ass}",
             "-af", audio_norme, *commun],
            "assemblage (centre)", cwd=dossier_travail)


def image_apercu(short, sortie_jpg, duree_s):
    executer_ffmpeg(["-ss", f"{duree_s / 2:.2f}", "-i", short,
                     "-frames:v", "1", "-q:v", "4", sortie_jpg], "aperçu")


def police_arabe_presente():
    r = subprocess.run(["fc-list", ":", "family"], capture_output=True, text=True)
    return "noto sans arabic" in r.stdout.lower()


# ----------------------------------------------------------------------
# Étape 7 — Habillage
# ----------------------------------------------------------------------
def valider_habillage(o):
    pbs = problemes_dict(o, [("titre", str), ("hook_texte_ecran", str),
                             ("caption", str), ("hashtags", list), ("cta", str)])
    if not pbs and not o["hashtags"]:
        pbs.append("« hashtags » ne doit pas être vide")
    return pbs


# ----------------------------------------------------------------------
# Orchestration
# ----------------------------------------------------------------------
def analyser_bornes_duree(texte):
    m = re.fullmatch(r"(\d+)-(\d+)", texte.strip())
    if not m:
        erreur_fatale("--duree-short attend le format min-max (ex. 15-60).")
    dmin, dmax = int(m.group(1)), int(m.group(2))
    if not 5 <= dmin < dmax <= 180:
        erreur_fatale("--duree-short : bornes incohérentes "
                      "(min ≥ 5, max ≤ 180, min < max).")
    return dmin, dmax


def main():
    parseur = argparse.ArgumentParser(
        description="MOTEUR DÉCOUPE V1 — vidéo longue → shorts 9:16 "
                    "sous-titrés, classés par potentiel.")
    parseur.add_argument("video", help="vidéo longue (10-90 min)")
    parseur.add_argument("--profil", required=True, help="profil créateur (json)")
    parseur.add_argument("--nb-shorts", type=int, default=8,
                         help="nombre de shorts à produire (défaut 8)")
    parseur.add_argument("--duree-short", default="15-60",
                         help="bornes de durée en secondes, format min-max "
                              "(défaut 15-60)")
    parseur.add_argument("--sous-titres", choices=["auto", "off"], default="auto",
                         help="sous-titres incrustés (défaut auto)")
    parseur.add_argument("--recadrage", choices=["visage", "centre", "flou"],
                         default="visage", help="recadrage 9:16 (défaut visage)")
    parseur.add_argument("--oui", action="store_true",
                         help="ne pas demander de confirmation avant les appels payants")
    args = parseur.parse_args()

    load_dotenv(DOSSIER_MODULE / ".env")
    load_dotenv()
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        erreur_fatale("ffmpeg/ffprobe introuvables. Installez ffmpeg.")
    cle_google = (os.environ.get("GOOGLE_API_KEY") or "").strip()
    if not cle_google:
        erreur_fatale("GOOGLE_API_KEY manquante (obligatoire). Copiez "
                      ".env.example vers .env puis renseignez votre clé Gemini.")
    cle_openai = (os.environ.get("OPENAI_API_KEY") or "").strip()
    modele_texte = (os.environ.get("GEMINI_MODELE_TEXTE") or MODELE_TEXTE_DEFAUT).strip()
    brut_budget = (os.environ.get("GEMINI_THINKING_BUDGET") or "").strip()
    thinking_budget = THINKING_BUDGET_DEFAUT
    if brut_budget:
        try:
            thinking_budget = int(brut_budget)
        except ValueError:
            thinking_budget = None
        if thinking_budget is None or thinking_budget < -1:
            erreur_fatale("GEMINI_THINKING_BUDGET invalide : entier attendu "
                          "(-1, 0 ou un plafond de tokens).")
    if not 1 <= args.nb_shorts <= 12:
        erreur_fatale("--nb-shorts doit être entre 1 et 12 (5 à 10 recommandé).")
    dmin, dmax = analyser_bornes_duree(args.duree_short)

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
    pbs_profil = problemes_dict(profil, SCHEMA_PROFIL)
    if pbs_profil:
        erreur_fatale("le profil créateur ne respecte pas le schéma attendu ("
                      + " ; ".join(pbs_profil) + ").")

    try:
        meta = ri.metadonnees(str(chemin_video))
    except Exception:  # noqa: BLE001
        erreur_fatale(f"impossible de lire la vidéo « {chemin_video} ».")
    duree_s = meta["duree_s"]
    if duree_s > DUREE_MAX_S:
        erreur_fatale(f"vidéo trop longue : {duree_s / 60:.0f} min "
                      f"(limite V1 : 90 min).")
    if not meta["a_de_l_audio"]:
        erreur_fatale("cette vidéo n'a pas de piste audio — impossible de "
                      "transcrire et de découper sur la parole.")
    if args.sous_titres == "auto" and not police_arabe_presente():
        print("⚠ Police « Noto Sans Arabic » introuvable — le rendu des "
              "sous-titres arabes/darija sera dégradé. Installez-la : "
              "sudo apt install fonts-noto-core")

    dossier_sortie = DOSSIER_MODULE / "sorties" / chemin_video.stem / "shorts"
    dossier_sortie.mkdir(parents=True, exist_ok=True)

    print(f"\nMOTEUR DÉCOUPE V1 — {chemin_video.name} "
          f"({duree_s / 60:.1f} min, {meta['largeur']}×{meta['hauteur']})")
    print(f"Objectif : {args.nb_shorts} short(s) de {dmin}-{dmax} s, "
          f"recadrage « {args.recadrage} », sous-titres {args.sous_titres}.")
    print(f"Sorties : {dossier_sortie}")

    # ---- Étape 1 — Ingestion adaptée --------------------------------
    etage(1, "Ingestion adaptée (signaux audio, sans API)")
    signaux = ingestion_adaptee(str(chemin_video), duree_s)

    # ---- Étape 2 — Transcription complète ---------------------------
    etage(2, "Transcription complète")
    gemini = Gemini(cle_google, thinking_budget)
    if cle_openai:
        transcription = transcrire_whisper_longue(
            str(chemin_video), cle_openai, duree_s, signaux["silences"], args.oui)
    else:
        transcription = transcrire_gemini_audio(
            gemini, modele_texte, str(chemin_video), duree_s, args.oui)
    segments = transcription["segments"]
    if not segments:
        erreur_fatale("transcription vide — la vidéo contient-elle de la parole ?")
    print(f"  ✓ {len(segments)} segments"
          + (f", {len(transcription['mots'])} mots horodatés"
             if transcription["mots"] else " (pas de timestamps mot à mot)"))

    # ---- Étape 3 — Sélection des candidats --------------------------
    etage(3, "Sélection des moments candidats (texte seul)")
    prompt_selection = (
        prompts.PROMPT_SELECTION
        .replace("{duree_min}", str(dmin)).replace("{duree_max}", str(dmax))
        + bloc_donnees("TRANSCRIPT HORODATÉ", transcript_horodate(segments))
        + bloc_donnees("SIGNAUX TECHNIQUES",
                       {"pics_energie_s": signaux["pics_energie_s"][:150],
                        "zones_plates": signaux["zones_plates"][:80],
                        "cuts_s": signaux["cuts_s"][:200]}))
    candidats_bruts = appel_texte_json(
        gemini, modele_texte, prompt_selection, valider_selection,
        "Étape 3 (sélection)", duree_s, 3000,
        "sélection des moments candidats (transcript, texte seul)", args.oui,
        temperature=0.4)
    candidats, rejetes_bornes = ajuster_aux_phrases(
        candidats_bruts, segments, duree_s, dmin, dmax)
    for r in rejetes_bornes:
        print(f"  ⚠ Candidat écarté ({r['raison_rejet']}) : "
              f"{r.get('titre_travail', '?')}")
    if not candidats:
        erreur_fatale("aucun candidat valide après vérification des bornes — "
                      "réessayez (ou élargissez --duree-short).")
    if len(candidats) < 12:
        print(f"  ⚠ Seulement {len(candidats)} candidat(s) valide(s) "
              "(12-20 attendus sur une vraie vidéo longue).")
    print(f"  ✓ {len(candidats)} candidat(s) aux bornes ajustées aux phrases.")

    # ---- Étape 4 — Scores et classement -----------------------------
    etage(4, "Scores et classement")
    pays_cible = premier_pays_cible(profil)
    culture = profil_culturel(gemini, modele_texte, pays_cible, duree_s, args.oui)
    liste_pour_score = [{"index": i, "de_s": c["de_s"], "a_s": c["a_s"],
                         "duree_s": c["duree_s"], "titre_travail": c["titre_travail"],
                         "type": c["type"], "resume": c["resume"],
                         "autonome": c["autonome"], "raison": c["raison"]}
                        for i, c in enumerate(candidats)]
    prompt_scoring = (prompts.PROMPT_SCORING
                      + bloc_donnees("MOMENTS CANDIDATS", liste_pour_score)
                      + bloc_donnees("PROFIL CRÉATEUR", profil)
                      + bloc_donnees("PROFIL CULTUREL", culture))
    notes = appel_texte_json(
        gemini, modele_texte, prompt_scoring, valider_scoring(len(candidats)),
        "Étape 4 (scoring)", duree_s, 2000,
        "notation des candidats (0-100)", args.oui, temperature=0.3)
    par_index = {n["index"]: n for n in notes}
    for i, c in enumerate(candidats):
        n = par_index.get(i)
        c["score"] = round(float(n["score"]), 1) if n else 0.0
        c["justification"] = n["justification"] if n else "non noté par le modèle"
        c["risque"] = (n or {}).get("risque")
    classes = sorted(candidats, key=lambda c: c["score"], reverse=True)
    autonomes = [c for c in classes if c.get("autonome")]
    non_autonomes = [{**c, "raison_rejet": "non autonome (contexte requis)"}
                     for c in classes if not c.get("autonome")]
    retenus = autonomes[:args.nb_shorts]
    retenus, chevauches = dedupliquer(retenus)
    manque = args.nb_shorts - len(retenus)
    if manque > 0:
        print(f"  ⚠ Seulement {len(retenus)} moment(s) autonome(s) valide(s) "
              f"sur les {args.nb_shorts} demandés — je livre ce qui existe, "
              "sans forcer de découpes faibles.")
    surplus = [{**c, "raison_rejet": f"score {c['score']} sous le seuil des "
                f"{args.nb_shorts} meilleurs"} for c in autonomes[args.nb_shorts:]]
    ecartes = rejetes_bornes + non_autonomes + surplus + chevauches
    print(f"  ✓ {len(retenus)} short(s) retenu(s), scores "
          f"{retenus[0]['score'] if retenus else 0} → "
          f"{retenus[-1]['score'] if retenus else 0} (estimation non calibrée).")

    # ---- Étapes 5-7 — Habillage puis rendu de chaque short ----------
    etage("5-7", "Habillage, découpe, recadrage, sous-titres")
    mots_globaux = transcription["mots"] or mots_depuis_segments(segments)
    shorts, rendu_rtl_verifie = [], False
    for rang, c in enumerate(retenus, 1):
        print(f"\n  ── Short {rang}/{len(retenus)} — {c['titre_travail']} "
              f"({c['duree_s']:.0f} s, score {c['score']})")
        extrait_texte = " ".join(s["texte"] for s in segments
                                 if s["de_s"] >= c["de_s"] - 0.5
                                 and s["a_s"] <= c["a_s"] + 0.5)
        prompt_habillage = (prompts.PROMPT_HABILLAGE
                           + bloc_donnees("TRANSCRIPT DE L'EXTRAIT", extrait_texte)
                           + bloc_donnees("TYPE", c["type"])
                           + bloc_donnees("PROFIL CRÉATEUR", profil)
                           + bloc_donnees("PROFIL CULTUREL", culture))
        habillage = appel_texte_json(
            gemini, modele_texte, prompt_habillage, valider_habillage,
            f"Habillage short {rang}", duree_s, 600,
            f"habillage du short {rang} (titre, hook, caption, hashtags)",
            args.oui, temperature=0.7)

        de = max(c["de_s"] - PAD_COUPE_S, 0.0)
        a = min(c["a_s"] + PAD_COUPE_S, duree_s)
        nom_fichier = f"short_{rang:02d}.mp4"
        try:
            with tempfile.TemporaryDirectory(prefix="decoupe_") as tmpdir:
                clip = os.path.join(tmpdir, "clip.mp4")
                extraire_clip(str(chemin_video), de, a, clip)
                video_recadree, mode_effectif = None, args.recadrage
                if args.recadrage == "visage":
                    traj = trajectoire_visage(clip)
                    if traj is None:
                        mode_effectif = "centre"
                        print("    Visage peu fiable → repli automatique « centre ».")
                    else:
                        video_recadree = os.path.join(tmpdir, "recadre.mp4")
                        recadrer_visage_opencv(clip, traj, video_recadree)
                avec_ass = args.sous_titres == "auto"
                if avec_ass:
                    mots_clip = [{"mot": m["mot"],
                                  "de_s": max(m["de_s"] - de, 0.0),
                                  "a_s": max(m["a_s"] - de, 0.05)}
                                 for m in mots_globaux
                                 if c["de_s"] - 0.3 <= m["de_s"] <= c["a_s"] + 0.3]
                    rtl = generer_ass(os.path.join(tmpdir, "sous_titres.ass"),
                                      mots_clip, habillage["hook_texte_ecran"],
                                      a - de)
                    rendu_rtl_verifie = rendu_rtl_verifie or rtl
                assembler_short(tmpdir, clip, video_recadree, mode_effectif,
                                avec_ass, str(dossier_sortie / nom_fichier))
            image_apercu(str(dossier_sortie / nom_fichier),
                         str(dossier_sortie / f"apercu_{rang:02d}.jpg"), a - de)
        except RuntimeError as e:
            print(f"    ✗ Rendu impossible ({e}) — short ignoré.")
            ecartes.append({**c, "raison_rejet": f"échec du rendu : {e}"})
            continue
        shorts.append({"rang": rang, "fichier": f"shorts/{nom_fichier}",
                       "apercu": f"apercu_{rang:02d}.jpg",
                       "de_s": c["de_s"], "a_s": c["a_s"],
                       "duree_s": c["duree_s"], "score": c["score"],
                       "type": c["type"], "titre_travail": c["titre_travail"],
                       "resume": c["resume"], "justification": c["justification"],
                       "risque": c.get("risque"), "recadrage": mode_effectif,
                       "habillage": habillage})
        print(f"    ✓ {nom_fichier} (recadrage {mode_effectif})")

    if not shorts:
        erreur_fatale("aucun short n'a pu être rendu.")

    # ---- Rapport + données -------------------------------------------
    etage("★", "Rapport")
    conso = gemini.conso
    cout_whisper = round(duree_s / 60 * COUT_WHISPER_PAR_MIN, 4) \
        if transcription["source"] == "whisper" else 0.0
    donnees = {
        "video": chemin_video.name,
        "genere_le": datetime.datetime.now().isoformat(timespec="seconds"),
        "duree_traitee_s": duree_s,
        "duree_traitee_min": round(duree_s / 60, 2),
        "parametres": {"nb_shorts": args.nb_shorts, "duree_min_s": dmin,
                       "duree_max_s": dmax, "recadrage": args.recadrage,
                       "sous_titres": args.sous_titres},
        "modeles": {"texte": modele_texte,
                    "transcription": transcription["source"]},
        "langue_rtl_detectee": rendu_rtl_verifie,
        "signaux": {k: v for k, v in signaux.items() if k != "silences"},
        "transcription": {"source": transcription["source"],
                          "nb_segments": len(segments),
                          "nb_mots": len(transcription["mots"] or [])},
        "segments": segments,
        "candidats": candidats,
        "shorts": shorts,
        "ecartes": [{"titre_travail": e.get("titre_travail", "?"),
                     "de_s": e.get("de_s"), "a_s": e.get("a_s"),
                     "score": e.get("score"),
                     "raison": e.get("raison_rejet", "?")} for e in ecartes],
        "consommation": {
            "whisper_minutes": round(duree_s / 60, 2)
            if transcription["source"] == "whisper" else 0,
            "whisper_cout_usd": cout_whisper,
            "gemini": {**conso,
                       "cout_usd_aux_tarifs_publies": round(gemini.cout_reel_usd(), 4)},
            "total_usd_aux_tarifs_publies": round(
                cout_whisper + gemini.cout_reel_usd(), 4)},
    }
    (dossier_sortie / "decoupe.json").write_text(
        json.dumps(donnees, ensure_ascii=False, indent=1), encoding="utf-8")
    generer_rapport_decoupe_html(donnees, dossier_sortie,
                                 dossier_sortie / "rapport_decoupe.html")

    print(f"\n✓ Terminé : {len(shorts)} short(s) dans {dossier_sortie}")
    print(f"   rapport : {dossier_sortie / 'rapport_decoupe.html'}")
    print(f"   données : {dossier_sortie / 'decoupe.json'}")
    total = donnees["consommation"]["total_usd_aux_tarifs_publies"]
    print(f"\nDurée traitée : {duree_s / 60:.1f} min | "
          f"Gemini : {fmt_tokens(conso['entree'])} entrée / "
          f"{fmt_tokens(conso['sortie'])} sortie / "
          f"{fmt_tokens(conso['reflexion'])} réflexion ({conso['appels']} appels)"
          + (f" | Whisper : {duree_s / 60:.1f} min" if cout_whisper else "")
          + f" — coût total ≈ {total:.4f} $ aux tarifs publiés.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nInterrompu par l'utilisateur.")
        sys.exit(130)
