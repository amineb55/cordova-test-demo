# Earnaura — site web

Landing Next.js (App Router) + Tailwind v4 pour Earnaura, la plateforme
construite autour des trois moteurs du dossier `moteur/` (`moteur.py`,
`decoupe.py`, `coach.py`). Voir `../../moteur/PRODUIT.md` pour le produit
et `BRIEF_SITE_WEB_EARNAURA.md` (fourni séparément) pour la spec complète
du MVP.

## Développement local

```bash
npm install
npm run dev
```

Ouvrir [http://localhost:3000](http://localhost:3000) — redirige vers
`/fr` ou `/en` selon la langue du navigateur.

## Structure

- `src/app/[locale]/` — pages par langue (`layout.tsx`, `page.tsx` la
  landing, `app/page.tsx` la page d'attente de l'application)
- `src/i18n/` — `config.ts` (locales, direction RTL par langue),
  `dictionnaires.ts` (tout le texte, FR + EN)
- `src/components/` — composants partagés (ex. `Tarifs.tsx`, bascule de
  zone de prix Maghreb/International)
- `src/middleware.ts` — redirection `/` → `/fr` ou `/en`

## Déploiement (Vercel)

Projet : import du dépôt GitHub `amineb55/cordova-test-demo`.

**Réglages obligatoires** (Settings → Git / General) :

| Réglage | Valeur |
|---|---|
| Root Directory | `site/earnaura` |
| Production Branch | `claude/new-session-p5cdog` (branche de travail — sera `main` une fois la PR mergée) |
| Framework Preset | **Next.js** — forcé par `vercel.json` |
| Output Directory | laisser vide (défaut du framework) |

Le `vercel.json` à la racine de ce dossier force `"framework": "nextjs"`, car
les réglages d'un fichier de configuration ont priorité sur ceux du tableau
de bord.

**Deuxième piège rencontré** : avec un Framework Preset sur « Other », le
déploiement réussit quand même (Vercel lance `npm run build`, donc `.next/`
est bien produit et le statut passe à *Ready*), mais Vercel ignore la sortie
de Next et sert uniquement `public/` comme dossier statique. Symptôme
caractéristique : `/health.txt` s'affiche, alors que **toutes** les pages de
l'application renvoient un `404: NOT_FOUND` de la plateforme — page blanche
Vercel, et non la page 404 de Next.js. Un 404 Vercel sur toutes les routes
alors qu'un fichier statique passe signifie donc « framework non détecté »,
jamais « erreur de routage applicatif ».

**Piège rencontré une fois** : Vercel n'associe pas un déploiement à une
branche « à la demande » — chaque déploiement se construit depuis la
branche et le commit qui étaient configurés **au moment où il a été
lancé**. Si vous changez le Root Directory ou la Production Branch
*après* un premier déploiement raté, le bouton **Redéployer** rejoue
l'ancienne configuration (ancienne branche, ancien commit) — il ne relit
pas les nouveaux réglages. Après tout changement de branche ou de
répertoire racine, il faut soit pousser un nouveau commit sur la bonne
branche, soit lancer un déploiement neuf (pas un « redeploy » d'un
déploiement existant) pour que les nouveaux réglages soient pris en
compte.

Ce dépôt vit sur `claude/new-session-p5cdog` tant que la PR n'est pas
mergée — `site/earnaura` n'existe pas sur `main`.
