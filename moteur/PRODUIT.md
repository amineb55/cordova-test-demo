# La plateforme — ce que fait le moteur, en une page

Le moteur analyse des vidéos courtes, décode ce qui les fait marcher, et
génère des plans d'action et des idées prêtes à tourner, adaptés au
profil du créateur (`profil.json` : niches, langue, pays cible, matériel,
style) et au profil culturel du pays visé (généré et mis en cache
automatiquement). Le darija et le mélange de langues sont traités comme
normaux, jamais « corrigés ».

## Mode A — analyser MA vidéo (diagnostic)

Pour une vidéo que tu as tournée : le moteur la regarde, l'écoute, mesure
le son et le rythme, puis livre un verdict (`pret_a_publier`,
`optimiser_avant`, `revoir`), 3 à 5 actions prioritaires horodatées avec
preuves, 3 hooks réécrits dans ta langue et un texte écran en 2 temps.

```bash
python moteur.py ma_video.mp4 --mode ma-video --profil profil.json
```

## Mode B — décoder une vidéo VIRALE (inspiration)

Pour une vidéo d'un autre créateur qui t'inspire : le moteur extrait la
FORMULE (le mécanisme, pas le sujet) puis génère 5 fiches idées qui
appliquent ce mécanisme à TA niche — jamais une copie. Chaque fiche :
titre-hook, script complet prêt à lire, textes écran, plan de tournage,
CTA, durée cible, sensibilité plateforme.

```bash
python moteur.py virale.mp4 --mode inspiration --profil profil.json
```

Options communes : `--crop-top 0.12 --crop-bottom 0.15` pour une capture
d'écran TikTok (ignore l'interface), `--oui` pour sauter les
confirmations de coût. Limite : vidéos de 3 minutes maximum.
Sorties dans `sorties/<nom>/` : `rapport.html`, `rapport.json`,
`timeline_attention.png`, `transcript.txt`.

## Moteur Découpe — vidéo longue → shorts

Pour une vidéo longue (jusqu'à 90 min) qui t'appartient : transcription
complète, sélection des meilleurs moments autonomes, notation 0-100,
découpe aux frontières de phrases, recadrage 9:16 (suivi du visage),
sous-titres mot à mot incrustés (darija/arabe gérés), son normalisé,
titre + caption + hashtags par short. Les shorts sortent classés par
potentiel, avec un rapport et les candidats écartés repêchables.

```bash
python decoupe.py longue.mp4 --profil profil.json --nb-shorts 8
# --duree-short 15-60 | --sous-titres auto|off | --recadrage visage|centre|flou
```

## La logique des crédits (coûts)

Chaque analyse consomme des crédits du moteur, proportionnels à la durée
de la vidéo et au travail demandé. Avant chaque opération payante, le
moteur affiche une estimation et attend la confirmation ; à la fin, la
consommation réelle est affichée et consignée dans le rapport. Ordres de
grandeur : quelques centimes pour une vidéo courte (mode A ou B),
0,50-0,90 $ pour découper une heure de vidéo. Les scores et estimations
sont des indications non calibrées — jamais des promesses de résultats.
