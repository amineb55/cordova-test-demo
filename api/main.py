# -*- coding: utf-8 -*-
"""
API EARNAURA — worker d'analyse (tranche B, mode fondateur)
===========================================================
Expose le moteur existant (moteur/moteur.py) en HTTP. Le moteur est
appelé EN SOUS-PROCESSUS, exactement comme le fait déjà coach.py :
aucune réécriture de sa logique.

Mode fondateur : un seul utilisateur, pas de comptes ni de paiement.
L'accès est protégé par un simple code partagé (EARNAURA_CODE_FONDATEUR)
afin que la clé API du propriétaire ne soit pas consommée par des tiers.
L'état des analyses vit en mémoire du conteneur — suffisant pour un seul
utilisateur ; la table Postgres arrive en tranche C avec les comptes.

Routes :
  GET  /sante                      état du service
  POST /analyses                   dépose une vidéo + mode → identifiant
  GET  /analyses/{id}              statut, étape courante, erreur éventuelle
  GET  /analyses/{id}/rapport      le rapport.json complet
  GET  /analyses/{id}/timeline.png le graphique de l'étage 1
  DELETE /analyses/{id}            supprime la vidéo et les sorties
"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

DOSSIER_API = Path(__file__).resolve().parent
DOSSIER_MOTEUR = Path(os.environ.get("EARNAURA_DOSSIER_MOTEUR",
                                     DOSSIER_API.parent / "moteur")).resolve()
DUREE_MAX_S = 180                      # limite du moteur (3 min)
TAILLE_MAX_OCTETS = 200 * 1024 * 1024  # 200 Mo
RETENTION_S = 60 * 60 * 6              # 6 h avant purge automatique
EXTENSIONS = {".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi"}

app = FastAPI(title="API Earnaura", version="1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[o.strip() for o in
                   (os.environ.get("EARNAURA_ORIGINES") or "*").split(",")],
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)

# identifiant → dict (statut, etape, erreur, dossier, créé_le…)
_analyses: dict[str, dict] = {}
_verrou = threading.Lock()

# Étapes affichées à l'utilisateur, déduites de la sortie réelle du moteur.
ETAPES = [
    {"cle": "ingestion", "libelle_fr": "Lecture de ta vidéo…",
     "libelle_en": "Reading your video…"},
    {"cle": "transcription", "libelle_fr": "Analyse du son et de la parole…",
     "libelle_en": "Analyzing sound and speech…"},
    {"cle": "comprehension", "libelle_fr": "Compréhension de ta langue…",
     "libelle_en": "Understanding your language…"},
    {"cle": "formule", "libelle_fr": "Décodage de la formule…",
     "libelle_en": "Decoding the formula…"},
    {"cle": "generation", "libelle_fr": "Rédaction de tes recommandations…",
     "libelle_en": "Writing your recommendations…"},
    {"cle": "rapport", "libelle_fr": "Mise en forme du rapport…",
     "libelle_en": "Formatting your report…"},
]
_ETAGE_VERS_ETAPE = {"1": "ingestion", "2": "transcription", "3": "comprehension",
                     "4": "formule", "5": "generation", "★": "rapport"}


# ----------------------------------------------------------------------
def _verifier_code(code_recu: str | None):
    """Verrou du mode fondateur — pas un système de comptes."""
    attendu = (os.environ.get("EARNAURA_CODE_FONDATEUR") or "").strip()
    if not attendu:
        return  # aucun code configuré : accès libre (développement local)
    if (code_recu or "").strip() != attendu:
        raise HTTPException(status_code=401,
                            detail="Code d'accès invalide.")


def _duree_video(chemin: Path) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(chemin)],
        capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout.strip():
        raise HTTPException(status_code=400,
                            detail="Fichier vidéo illisible ou format non "
                                   "supporté.")
    return float(r.stdout.strip())


def _purger_anciennes():
    """Supprime les analyses expirées (vidéos comprises)."""
    maintenant = time.time()
    with _verrou:
        expirees = [i for i, a in _analyses.items()
                    if maintenant - a["cree_le"] > RETENTION_S]
        for identifiant in expirees:
            shutil.rmtree(_analyses[identifiant]["dossier"], ignore_errors=True)
            _analyses.pop(identifiant, None)


def _executer_moteur(identifiant: str, chemin_video: Path, mode: str,
                     crop_haut: float, crop_bas: float):
    """Lance moteur.py en sous-processus et suit sa progression réelle."""
    analyse = _analyses[identifiant]
    commande = [
        "python", str(DOSSIER_MOTEUR / "moteur.py"), str(chemin_video),
        "--mode", mode, "--profil", str(DOSSIER_MOTEUR / "profil.json"),
        "--oui", "--crop-top", str(crop_haut), "--crop-bottom", str(crop_bas),
    ]
    journal: list[str] = []
    try:
        processus = subprocess.Popen(
            commande, cwd=str(DOSSIER_MOTEUR), stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)
        for ligne in processus.stdout:
            journal.append(ligne.rstrip())
            trouve = re.search(r"ÉTAGE\s+(\S+)\s+—", ligne)
            if trouve:
                etape = _ETAGE_VERS_ETAPE.get(trouve.group(1))
                if etape:
                    with _verrou:
                        analyse["etape"] = etape
        processus.wait()
        code = processus.returncode
    except Exception as e:  # noqa: BLE001
        with _verrou:
            analyse.update(statut="erreur",
                           erreur=f"Le moteur n'a pas pu démarrer ({e}).")
        return

    sortie = "\n".join(journal)
    if code != 0:
        # le moteur écrit ses erreurs prévisibles en français clair
        message = "L'analyse a échoué."
        marqueur = re.search(r"✗ ERREUR : (.+)", sortie)
        if marqueur:
            message = marqueur.group(1).strip()
        with _verrou:
            analyse.update(statut="erreur", erreur=message, journal=sortie[-4000:])
        return

    dossier_sorties = DOSSIER_MOTEUR / "sorties" / chemin_video.stem
    chemin_rapport = dossier_sorties / "rapport.json"
    if not chemin_rapport.exists():
        with _verrou:
            analyse.update(statut="erreur",
                           erreur="L'analyse s'est terminée sans produire de "
                                  "rapport.", journal=sortie[-4000:])
        return

    # on rapatrie les sorties dans le dossier de l'analyse, puis on efface
    # la vidéo source : elle n'est plus nécessaire
    for nom in ("rapport.json", "rapport.html", "timeline_attention.png",
                "transcript.txt"):
        origine = dossier_sorties / nom
        if origine.exists():
            shutil.copy2(origine, analyse["dossier"] / nom)
    shutil.rmtree(dossier_sorties, ignore_errors=True)
    chemin_video.unlink(missing_ok=True)

    with _verrou:
        analyse.update(statut="termine", etape="rapport",
                       termine_le=time.time())


# ----------------------------------------------------------------------
@app.get("/sante")
def sante():
    moteur_present = (DOSSIER_MOTEUR / "moteur.py").exists()
    cle_presente = bool((os.environ.get("GOOGLE_API_KEY") or "").strip())
    return {
        "service": "api-earnaura",
        "moteur_present": moteur_present,
        "cle_google_configuree": cle_presente,
        "ffmpeg": shutil.which("ffmpeg") is not None,
        "analyses_en_memoire": len(_analyses),
        "etapes": ETAPES,
    }


@app.post("/analyses")
async def creer_analyse(
    video: UploadFile = File(...),
    mode: str = Form("ma-video"),
    crop_haut: float = Form(0.0),
    crop_bas: float = Form(0.0),
    x_code_fondateur: str | None = Header(default=None),
):
    _verifier_code(x_code_fondateur)
    _purger_anciennes()

    if mode not in ("ma-video", "inspiration"):
        raise HTTPException(status_code=400,
                            detail="Mode invalide (ma-video ou inspiration).")
    extension = Path(video.filename or "").suffix.lower()
    if extension not in EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail=f"Format non supporté ({extension or 'inconnu'}). "
                   f"Formats acceptés : {', '.join(sorted(EXTENSIONS))}.")

    identifiant = uuid.uuid4().hex[:12]
    dossier = Path(tempfile.mkdtemp(prefix=f"earnaura_{identifiant}_"))
    # nom de fichier neutre : il devient le nom du dossier de sortie du moteur
    chemin_video = dossier / f"video_{identifiant}{extension}"

    taille = 0
    with open(chemin_video, "wb") as destination:
        while morceau := await video.read(1024 * 1024):
            taille += len(morceau)
            if taille > TAILLE_MAX_OCTETS:
                destination.close()
                shutil.rmtree(dossier, ignore_errors=True)
                raise HTTPException(
                    status_code=413,
                    detail="Fichier trop lourd (200 Mo maximum).")
            destination.write(morceau)

    try:
        duree = _duree_video(chemin_video)
    except HTTPException:
        shutil.rmtree(dossier, ignore_errors=True)
        raise
    if duree > DUREE_MAX_S:
        shutil.rmtree(dossier, ignore_errors=True)
        raise HTTPException(
            status_code=400,
            detail=f"Vidéo trop longue : {duree:.0f} s. L'analyse accepte "
                   f"{DUREE_MAX_S} s au maximum (3 minutes).")

    with _verrou:
        _analyses[identifiant] = {
            "identifiant": identifiant, "statut": "en_cours", "etape": "ingestion",
            "mode": mode, "duree_s": round(duree, 1), "erreur": None,
            "dossier": dossier, "cree_le": time.time(),
            "nom_fichier": video.filename,
        }
    threading.Thread(
        target=_executer_moteur,
        args=(identifiant, chemin_video, mode, crop_haut, crop_bas),
        daemon=True).start()

    return {"identifiant": identifiant, "statut": "en_cours",
            "duree_s": round(duree, 1), "mode": mode}


@app.get("/analyses/{identifiant}")
def statut_analyse(identifiant: str,
                   x_code_fondateur: str | None = Header(default=None)):
    _verifier_code(x_code_fondateur)
    with _verrou:
        analyse = _analyses.get(identifiant)
        if analyse is None:
            raise HTTPException(status_code=404, detail="Analyse introuvable.")
        return {
            "identifiant": identifiant, "statut": analyse["statut"],
            "etape": analyse["etape"], "erreur": analyse["erreur"],
            "mode": analyse["mode"], "duree_s": analyse["duree_s"],
            "nom_fichier": analyse["nom_fichier"],
        }


@app.get("/analyses/{identifiant}/rapport")
def rapport_analyse(identifiant: str,
                    x_code_fondateur: str | None = Header(default=None)):
    _verifier_code(x_code_fondateur)
    with _verrou:
        analyse = _analyses.get(identifiant)
    if analyse is None:
        raise HTTPException(status_code=404, detail="Analyse introuvable.")
    if analyse["statut"] != "termine":
        raise HTTPException(status_code=409,
                            detail="L'analyse n'est pas encore terminée.")
    chemin = analyse["dossier"] / "rapport.json"
    if not chemin.exists():
        raise HTTPException(status_code=404, detail="Rapport introuvable.")
    return JSONResponse(json.loads(chemin.read_text(encoding="utf-8")))


@app.get("/analyses/{identifiant}/timeline.png")
def timeline_analyse(identifiant: str, code: str | None = None):
    # image affichée directement par le navigateur : le code passe en query
    _verifier_code(code)
    with _verrou:
        analyse = _analyses.get(identifiant)
    if analyse is None:
        raise HTTPException(status_code=404, detail="Analyse introuvable.")
    chemin = analyse["dossier"] / "timeline_attention.png"
    if not chemin.exists():
        raise HTTPException(status_code=404, detail="Graphique introuvable.")
    return FileResponse(chemin, media_type="image/png")


@app.delete("/analyses/{identifiant}")
def supprimer_analyse(identifiant: str,
                      x_code_fondateur: str | None = Header(default=None)):
    _verifier_code(x_code_fondateur)
    with _verrou:
        analyse = _analyses.pop(identifiant, None)
    if analyse is None:
        raise HTTPException(status_code=404, detail="Analyse introuvable.")
    shutil.rmtree(analyse["dossier"], ignore_errors=True)
    return {"supprime": True}
