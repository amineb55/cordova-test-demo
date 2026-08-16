# MOTEUR V1 — Analyse & génération pour vidéos courtes

Un outil en ligne de commande qui prend une vidéo courte (≤ 3 minutes) et
produit un rapport complet : diagnostic de **ma** vidéo (mode A) ou
décodage d'une vidéo **virale** qui m'inspire, avec formule réutilisable
et 5 fiches idées (mode B). Pensé pour les créateurs Maroc / darija
(le mélange darija/français/anglais est traité comme normal, jamais
« corrigé »).

## Les 5 étages

1. **Ingestion locale** (gratuit, sans API) — métadonnées, LUFS/true peak,
   silences, cuts, timeline de mouvement, zones plates, pics, visage.
2. **Transcription** (Whisper, si clé OpenAI) — timestamps par segment et
   par mot, débit de parole par tranche de 5 s.
3. **Compréhension vidéo** (Gemini, obligatoire) — Gemini regarde ET
   écoute la vidéo entière.
4. **Décodeur** — extraction de la FORMULE (le mécanisme, pas le sujet).
5. **Générateur** — diagnostic priorisé (mode A) ou 5 fiches idées (mode B).

## Installation

Prérequis : Python 3.10+, `ffmpeg`/`ffprobe` dans le PATH.

```bash
# 1. ffmpeg
sudo apt install ffmpeg        # Debian/Ubuntu
brew install ffmpeg            # macOS

# 2. dépendances Python
cd moteur
pip install -r requirements.txt

# 3. clés API
cp .env.example .env
# éditer .env : GOOGLE_API_KEY obligatoire, OPENAI_API_KEY optionnelle
```

Modèles utilisés (vérifiés dans la documentation officielle, août 2026) :
`gemini-3.6-flash` (famille Flash la plus récente, accepte la vidéo) et
`whisper-1` (seul modèle OpenAI avec timestamps segment/mot). Ils se
remplacent via `GEMINI_MODELE_VIDEO` / `GEMINI_MODELE_TEXTE` dans `.env`.
La réflexion (thinking) de Gemini est plafonnée à 2048 tokens par appel,
réglable via `GEMINI_THINKING_BUDGET` (`-1` automatique, `0` désactivée) ;
ces tokens sont comptés dans l'estimation de coût affichée.

## Usage — 3 exemples

```bash
# 1) Mode A — analyser MA vidéo (diagnostic + corrections)
python moteur.py video.mp4 --mode ma-video --profil profil.json

# 2) Mode B — décoder une vidéo VIRALE qui m'inspire (formule + 5 fiches)
python moteur.py viral.mp4 --mode inspiration --profil profil.json

# 3) Capture d'écran TikTok (interface à ignorer en haut/bas) + sans
#    demande de confirmation des coûts
python moteur.py capture.mp4 --mode inspiration --profil profil.json \
    --crop-top 0.12 --crop-bottom 0.15 --oui
```

Avant chaque appel Gemini, le moteur affiche la durée de la vidéo et une
estimation du coût, puis demande confirmation (`--oui` pour passer outre).
Les vidéos de plus de 3 minutes sont refusées (limite V1).

## Sorties

Dossier `sorties/<nom-video>/` :

| Fichier | Contenu |
|---|---|
| `rapport.html` | rapport lisible (une page autonome, mobile, fond sombre) |
| `rapport.json` | toutes les données brutes des 5 étages |
| `timeline_attention.png` | graphique mouvement visuel + énergie audio |
| `transcript.txt` | transcript horodaté (Whisper) ou texte (Gemini) |

## profil.json

Décrit le créateur (niches, langues, pays cible, matériel, style) — voir
l'exemple fourni. Au premier usage d'un pays cible, un profil culturel est
généré par LLM et mis en cache dans `data/cultures/<pays>.json`.

## Notes

- Toutes les évaluations affichées sont des **estimations non calibrées**.
- Règle darija : la transcription est conservée telle quelle, jamais
  « corrigée » vers l'arabe classique ; le code-switching est normal.
- Critère de succès du produit : sur 10 fiches idées générées, en tourner
  au moins 3 telles quelles. Si le darija généré est raide ou les idées
  génériques, on itère sur les prompts (`prompts.py`), pas sur
  l'architecture.
