# API Earnaura — worker d'analyse (tranche B)

Expose le moteur (`moteur/moteur.py`) en HTTP. Le moteur est appelé **en
sous-processus**, comme le fait déjà `coach.py` : aucune réécriture de sa
logique, conformément au brief.

## Routes

| Route | Rôle |
|---|---|
| `GET /sante` | état du service (moteur présent, clé configurée, ffmpeg) |
| `POST /analyses` | dépose une vidéo (`video`, `mode`, `crop_haut`, `crop_bas`) → identifiant |
| `GET /analyses/{id}` | statut et étape courante, déduits de la sortie réelle du moteur |
| `GET /analyses/{id}/rapport` | le `rapport.json` complet |
| `GET /analyses/{id}/timeline.png` | le graphique de l'étage 1 |
| `DELETE /analyses/{id}` | supprime la vidéo et ses sorties |

## Variables d'environnement

| Variable | Rôle |
|---|---|
| `GOOGLE_API_KEY` | **obligatoire** — clé du moteur |
| `EARNAURA_CODE_FONDATEUR` | code d'accès partagé ; vide = accès libre (dev local) |
| `EARNAURA_ORIGINES` | origines CORS autorisées, séparées par des virgules |
| `OPENAI_API_KEY` | optionnelle — transcription Whisper mot à mot |

## Développement local

```bash
pip install -r api/requirements.txt -r moteur/requirements.txt
export GOOGLE_API_KEY=...
python -m uvicorn api.main:app --port 8080     # depuis la racine du dépôt
```

## Déploiement sur Cloud Run

Depuis **[Google Cloud Shell](https://shell.cloud.google.com)** (aucune
installation nécessaire, les identifiants y sont déjà présents) :

```bash
git clone -b claude/new-session-p5cdog \
  https://github.com/amineb55/cordova-test-demo.git && cd cordova-test-demo

gcloud run deploy earnaura-api \
  --source . \
  --region europe-west1 \
  --allow-unauthenticated \
  --memory 2Gi --cpu 2 --timeout 900 \
  --min-instances 1 --max-instances 1 \
  --set-env-vars "GOOGLE_API_KEY=VOTRE_CLE,EARNAURA_CODE_FONDATEUR=VOTRE_CODE"
```

Notes :

- `--min-instances 1 --max-instances 1` : l'état des analyses vit en
  mémoire (mode fondateur, un seul utilisateur). Une seule instance
  garantit qu'un sondage de statut retombe sur l'instance qui traite le
  job. La table Postgres de la tranche C lèvera cette contrainte.
- `--timeout 900` couvre largement une analyse de 3 minutes de vidéo
  (mesuré : ~2 minutes pour une vidéo de 11 s, dominé par les appels au
  modèle, pas par la durée de la vidéo).
- La commande affiche l'URL du service à la fin. Reportez-la dans Vercel
  (**Settings → Environment Variables**) sous `NEXT_PUBLIC_API_URL`, puis
  redéployez le front pour qu'il la prenne en compte.
- Renseignez ensuite `EARNAURA_ORIGINES` avec l'URL du site pour
  restreindre le CORS.

## Limites assumées du mode fondateur

Ni comptes, ni crédits, ni paiement : c'est la tranche C. L'accès est
protégé par un code partagé (`EARNAURA_CODE_FONDATEUR`) dont le seul rôle
est d'empêcher un tiers de consommer la clé API du propriétaire. Les
vidéos vivent sur le disque éphémère du conteneur et sont supprimées dès
la fin de l'analyse ; les rapports sont purgés au bout de 6 heures.
