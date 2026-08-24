# Worker d'analyse Earnaura — conteneur Cloud Run
# Contient ffmpeg + OpenCV (dépendances du moteur) et le moteur lui-même.
#
# Ce fichier DOIT rester à la racine du dépôt : `gcloud run deploy --source .`
# ne cherche un Dockerfile qu'à la racine du contexte de build. S'il n'en
# trouve pas, il bascule sur les Buildpacks, qui détectent alors le
# package.json Cordova présent à la racine et construisent une application
# Node.js — le build réussit, mais le conteneur n'écoute sur aucun port et
# Cloud Run échoue au démarrage.
FROM python:3.11-slim

# ffmpeg pour l'ingestion/extraction audio, les bibliothèques système
# requises par opencv-python-headless, et les polices Noto (rendu arabe).
RUN apt-get update && apt-get install -y --no-install-recommends \
      ffmpeg libgl1 libglib2.0-0 fonts-noto-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Dépendances du moteur puis celles de l'API (couches de cache séparées)
COPY moteur/requirements.txt /app/moteur/requirements.txt
RUN pip install --no-cache-dir -r /app/moteur/requirements.txt
COPY api/requirements.txt /app/api/requirements.txt
RUN pip install --no-cache-dir -r /app/api/requirements.txt

COPY moteur/ /app/moteur/
COPY api/ /app/api/

ENV EARNAURA_DOSSIER_MOTEUR=/app/moteur \
    PYTHONUNBUFFERED=1 \
    PORT=8080

# Une seule instance de worker : l'état des analyses vit en mémoire
# (mode fondateur). Le timeout couvre une analyse de 3 minutes de vidéo.
CMD exec uvicorn api.main:app --host 0.0.0.0 --port ${PORT:-8080} --workers 1
