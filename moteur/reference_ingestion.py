# -*- coding: utf-8 -*-
"""
ÉTAGE 1 DU MOTEUR — INGESTION LOCALE (gratuit, sans aucune API)
================================================================
Extrait d'une vidéo courte :
  - métadonnées (durée, résolution, fps, présence audio)
  - loudness intégré (LUFS) + true peak, comme les plateformes
  - silences
  - cuts (changements de scène)
  - timeline de mouvement visuel (zones plates = risque de décrochage, pics)
  - présence du visage (approximatif)
  - graphique timeline_attention.png + ingestion.json

Usage :
  python reference_ingestion.py video.mp4 [--crop-top 0.12 --crop-bottom 0.15]

  --crop-top / --crop-bottom : proportion de l'image à ignorer en haut/bas.
  Utile pour les captures d'écran TikTok (interface). Mettre 0 0 pour une
  vidéo brute sans interface.

Dépendances : ffmpeg/ffprobe (binaires), puis
  pip install opencv-python-headless numpy scipy matplotlib
Code testé et fonctionnel — À INTÉGRER TEL QUEL, ne pas réécrire.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

import cv2
import numpy as np
from scipy.io import wavfile

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

STEP = 0.25  # résolution de la timeline, en secondes


# ----------------------------------------------------------------------
def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def metadonnees(video):
    r = run(["ffprobe", "-v", "error", "-print_format", "json",
             "-show_format", "-show_streams", video])
    info = json.loads(r.stdout)
    meta = {"duree_s": round(float(info["format"]["duration"]), 2),
            "a_de_l_audio": False, "largeur": None, "hauteur": None, "fps": None}
    for s in info["streams"]:
        if s["codec_type"] == "video":
            meta["largeur"], meta["hauteur"] = s["width"], s["height"]
            num, den = s.get("r_frame_rate", "0/1").split("/")
            meta["fps"] = round(float(num) / float(den), 1) if float(den) else None
        elif s["codec_type"] == "audio":
            meta["a_de_l_audio"] = True
    return meta


def loudness(video):
    """LUFS intégré + true peak (cible plateformes ≈ -14 LUFS)."""
    r = run(["ffmpeg", "-hide_banner", "-i", video,
             "-af", "loudnorm=print_format=json", "-f", "null", "-"])
    m = re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", r.stderr, re.S)
    if not m:
        return None
    d = json.loads(m.group(0))
    return {"lufs_integre": float(d["input_i"]),
            "true_peak_db": float(d["input_tp"]),
            "plage_dynamique": float(d["input_lra"])}


def silences(video, seuil_db=-35, duree_min=0.4):
    r = run(["ffmpeg", "-hide_banner", "-i", video,
             "-af", f"silencedetect=noise={seuil_db}dB:d={duree_min}",
             "-f", "null", "-"])
    starts = [float(x) for x in re.findall(r"silence_start: ([0-9.]+)", r.stderr)]
    ends = [float(x) for x in re.findall(r"silence_end: ([0-9.]+)", r.stderr)]
    return [{"de_s": round(a, 1), "a_s": round(b, 1)} for a, b in zip(starts, ends)]


def cuts(video, seuil=0.35):
    r = run(["ffmpeg", "-hide_banner", "-i", video,
             "-vf", f"select='gt(scene,{seuil})',showinfo", "-f", "null", "-"])
    return [round(float(x), 1) for x in re.findall(r"pts_time:([0-9.]+)", r.stderr)]


def energie_audio(video):
    """Courbe RMS (dB) par fenêtre de STEP secondes."""
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False).name
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-i", video, "-ac", "1", "-ar", "16000", tmp])
    sr, a = wavfile.read(tmp)
    os.unlink(tmp)
    a = a.astype(np.float32) / 32768.0
    win = int(sr * STEP)
    n = max(len(a) // win, 1)
    rms = np.array([np.sqrt(np.mean(a[i * win:(i + 1) * win] ** 2)) for i in range(n)])
    db = 20 * np.log10(rms + 1e-9)
    t = np.arange(n) * STEP
    return t, db


def mouvement_et_visage(video, crop_top, crop_bottom):
    cap = cv2.VideoCapture(video)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30
    dur = cap.get(cv2.CAP_PROP_FRAME_COUNT) / fps
    frontal = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    profil = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_profileface.xml")
    times, motion, faces = [], [], []
    prev, t = None, 0.0
    while t < dur:
        cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000)
        ok, frame = cap.read()
        if not ok:
            break
        h = frame.shape[0]
        roi = frame[int(h * crop_top):int(h * (1 - crop_bottom)), :]
        g = cv2.cvtColor(cv2.resize(roi, (270, 480)), cv2.COLOR_BGR2GRAY)
        g = cv2.GaussianBlur(g, (5, 5), 0)
        if prev is not None:
            motion.append(float(np.mean(cv2.absdiff(g, prev))))
            times.append(t)
        prev = g
        f = frontal.detectMultiScale(g, 1.1, 4)
        if len(f) == 0:
            f = profil.detectMultiScale(g, 1.1, 4)
        faces.append(len(f) > 0)
        t += STEP
    cap.release()
    m = np.array(motion)
    m_norm = m / (m.max() + 1e-9) * 100
    return np.array(times), m_norm, faces, dur


def zones_et_pics(times, m_norm):
    med = float(np.median(m_norm))
    seuil_pic = float(np.percentile(m_norm, 90))
    pics = [round(float(times[i]), 1) for i in range(1, len(m_norm))
            if m_norm[i] > seuil_pic and m_norm[i - 1] <= seuil_pic]
    zones, i = [], 0
    while i < len(m_norm):
        j = i
        while j < len(m_norm) and m_norm[j] < med:
            j += 1
        if (j - i) * STEP >= 1.75:
            zones.append({"de_s": round(float(times[i]), 1),
                          "a_s": round(float(times[min(j, len(times) - 1)]), 1)})
        i = max(j, i + 1)
    return zones, pics, med


def graphique(times, m_norm, med, zones, at, adb, out_png):
    plt.rcParams.update({
        "figure.facecolor": "#0f1220", "axes.facecolor": "#0f1220",
        "axes.edgecolor": "#3a3f5c", "text.color": "#e8eaf6",
        "axes.labelcolor": "#e8eaf6", "xtick.color": "#aab0d0",
        "ytick.color": "#aab0d0", "font.size": 11})
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(8.5, 6.2), sharex=True,
                                   gridspec_kw={"hspace": 0.28})
    fig.suptitle("TIMELINE D'ATTENTION — analyse automatique",
                 fontsize=14, fontweight="bold", color="#ffffff", y=0.97)
    ax1.plot(times, m_norm, color="#5ee6a8", lw=2.2)
    ax1.fill_between(times, m_norm, color="#5ee6a8", alpha=0.15)
    ax1.axhline(med, color="#8890b5", ls=":", lw=1)
    for z in zones:
        ax1.axvspan(z["de_s"], z["a_s"], color="#ff5c5c", alpha=0.18)
        ax1.text((z["de_s"] + z["a_s"]) / 2, 92, "⚠ zone plate",
                 ha="center", color="#ff8a8a", fontsize=9)
    ax1.set_ylabel("Mouvement visuel")
    ax1.set_ylim(0, 105)
    ax2.plot(at, np.clip(adb, -50, 0), color="#7aa8ff", lw=2.2)
    ax2.fill_between(at, np.clip(adb, -50, 0), -50, color="#7aa8ff", alpha=0.15)
    ax2.set_ylabel("Énergie audio (dB)")
    ax2.set_xlabel("secondes")
    ax2.set_ylim(-50, 0)
    for ax in (ax1, ax2):
        for s in ("top", "right"):
            ax.spines[s].set_visible(False)
        ax.set_xlim(0, float(times[-1]) if len(times) else 1)
    plt.savefig(out_png, dpi=160, bbox_inches="tight")


# ----------------------------------------------------------------------
def ingerer(video, crop_top=0.12, crop_bottom=0.15,
            out_json="ingestion.json", out_png="timeline_attention.png"):
    meta = metadonnees(video)
    res = {"fichier": os.path.basename(video), **meta}
    if meta["a_de_l_audio"]:
        res["loudness"] = loudness(video)
        res["silences"] = silences(video)
        at, adb = energie_audio(video)
    else:
        res["loudness"], res["silences"], at, adb = None, [], np.array([0]), np.array([-50])
    res["cuts_s"] = cuts(video)
    times, m_norm, faces, _ = mouvement_et_visage(video, crop_top, crop_bottom)
    zones, pics, med = zones_et_pics(times, m_norm)
    res["zones_plates"] = zones
    res["pics_visuels_s"] = pics
    res["visage_present_pct"] = round(100 * sum(faces) / max(len(faces), 1))
    res["timeline"] = {"pas_s": STEP,
                       "mouvement": [round(float(x), 1) for x in m_norm],
                       "audio_db": [round(float(x), 1) for x in adb]}
    graphique(times, m_norm, med, zones, at, adb, out_png)
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    return res


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("video")
    p.add_argument("--crop-top", type=float, default=0.12)
    p.add_argument("--crop-bottom", type=float, default=0.15)
    a = p.parse_args()
    r = ingerer(a.video, a.crop_top, a.crop_bottom)
    print(json.dumps({k: v for k, v in r.items() if k != "timeline"},
                     ensure_ascii=False, indent=1))
