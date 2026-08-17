# -*- coding: utf-8 -*-
"""
Génération de rapport_decoupe.html — même charte que le rapport du moteur
(une page autonome, fond sombre, mobile). En tête : la vidéo source et le
nombre de shorts. Puis les shorts en cartes classées par score (aperçu,
badge score, durée, type, titre, hook, caption + hashtags copiables,
« pourquoi ce moment », chemin du fichier). En bas : les candidats
écartés (titre + raison) pour repêchage.
"""
import base64
from pathlib import Path

from rapport import MENTION, STYLE_SOMBRE, e, fmt_s

STYLE_DECOUPE = """
 .apercu { display: flex; gap: 14px; }
 .apercu img { width: 132px; aspect-ratio: 9/16; object-fit: cover;
               border-radius: 10px; flex-shrink: 0; }
 .apercu .infos { flex: 1; min-width: 0; }
 .score { font-size: 1.05rem; font-weight: 800; padding: 4px 12px;
          border-radius: 999px; display: inline-block; }
 .score.haut { background: #123726; color: #5ee6a8; }
 .score.moyen { background: #3a2b12; color: #ffc46b; }
 .score.bas { background: #3a1519; color: #ff8a8a; }
 .copiable { position: relative; background: #10142a; border: 1px dashed
             #2a2f4d; border-radius: 8px; padding: 10px 12px; margin: 8px 0; }
 .copiable p { margin: 0; padding-right: 74px; white-space: pre-wrap;
               word-break: break-word; }
 .copier { position: absolute; top: 8px; right: 8px; background: #2a2f4d;
           color: #e8eaf6; border: none; border-radius: 6px; padding: 5px 10px;
           font-size: .78rem; cursor: pointer; }
 .copier:active { background: #5ee6a8; color: #0f1220; }
 .chemin { font-family: ui-monospace, monospace; font-size: .8rem;
           color: #7aa8ff; word-break: break-all; }
 .hook-haut { background: #10142a; border-left: 3px solid #5ee6a8;
              padding: 6px 10px; border-radius: 6px; }
"""

SCRIPT_COPIE = """
document.querySelectorAll('.copier').forEach(function (b) {
  b.addEventListener('click', function () {
    var cible = document.getElementById(b.dataset.cible);
    navigator.clipboard.writeText(cible.innerText).then(function () {
      b.textContent = 'Copié ✓';
      setTimeout(function () { b.textContent = 'Copier'; }, 1500);
    });
  });
});
"""


def _classe_score(score):
    try:
        s = float(score)
    except (TypeError, ValueError):
        return "bas"
    return "haut" if s >= 70 else ("moyen" if s >= 45 else "bas")


def _apercu_base64(dossier, nom):
    chemin = Path(dossier) / str(nom)
    if nom and chemin.exists():
        return base64.b64encode(chemin.read_bytes()).decode("ascii")
    return ""


def _carte_short(short, dossier):
    h = short.get("habillage") or {}
    b64 = _apercu_base64(dossier, short.get("apercu"))
    image = (f"<img alt='aperçu' src='data:image/jpeg;base64,{b64}'>"
             if b64 else "")
    hashtags = " ".join(t if str(t).startswith("#") else f"#{t}"
                        for t in (h.get("hashtags") or []))
    ident = f"cap{short.get('rang', 0)}"
    risque = ""
    if short.get("risque"):
        risque = f"<p class='alerte'>⚠ Risque signalé : {e(short['risque'])}</p>"
    return f"""
 <div class="carte">
  <div class="apercu">
   {image}
   <div class="infos">
    <div class="entete-action">
     <span class="score {_classe_score(short.get('score'))}">{e(short.get('score'))}/100</span>
     <span class="ts">{fmt_s(short.get('duree_s'))}</span>
     <span class="badge orange">{e(short.get('type'))}</span>
    </div>
    <h3 class="titre-hook">{e(h.get('titre') or short.get('titre_travail'))}</h3>
    <p class="hook-haut">Hook à l'écran : <strong>{e(h.get('hook_texte_ecran'))}</strong></p>
    <p class="meta">{fmt_s(short.get('de_s'))} → {fmt_s(short.get('a_s'))}
     dans la vidéo source · recadrage {e(short.get('recadrage'))}</p>
   </div>
  </div>
  <p><strong>Pourquoi ce moment :</strong> {e(short.get('justification'))}</p>
  {risque}
  <h4>Caption + hashtags (copiables)</h4>
  <div class="copiable">
   <p id="{ident}">{e(h.get('caption'))}

{e(hashtags)}</p>
   <button class="copier" data-cible="{ident}">Copier</button>
  </div>
  <p><strong>CTA :</strong> <span class="cta">{e(h.get('cta'))}</span></p>
  <p class="chemin">{e(short.get('fichier'))}</p>
 </div>"""


def _section_ecartes(ecartes):
    lignes = []
    for c in ecartes:
        bornes = ""
        if c.get("de_s") is not None:
            bornes = (f"<span class='ts'>{fmt_s(c.get('de_s'))} → "
                      f"{fmt_s(c.get('a_s'))}</span> ")
        score = f" (score {e(c['score'])})" if c.get("score") is not None else ""
        lignes.append(f"<li>{bornes}<strong>{e(c.get('titre_travail'))}</strong>"
                      f"{score} — {e(c.get('raison'))}</li>")
    return f"""
<section>
 <details>
  <summary><h2 class="h2-inline">Candidats écartés ({len(ecartes)}) —
   à repêcher si besoin</h2></summary>
  <ul>{''.join(lignes) or '<li>—</li>'}</ul>
 </details>
</section>"""


def generer_rapport_decoupe_html(donnees, dossier_shorts, chemin_html):
    shorts = donnees.get("shorts") or []
    conso = donnees.get("consommation") or {}
    cartes = "".join(_carte_short(s, dossier_shorts) for s in shorts)
    source_transcription = (donnees.get("transcription") or {}).get("source", "?")
    contenu = f"""<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Découpe — {e(donnees.get('video'))}</title>
<style>{STYLE_SOMBRE}{STYLE_DECOUPE}</style>
</head>
<body>
<main>
<section>
 <h1>{e(donnees.get('video'))}</h1>
 <p class="sous-titre">Moteur Découpe — {len(shorts)} short(s) prêt(s) à
  publier, classés par potentiel</p>
 <p class="meta">Généré le {e(donnees.get('genere_le'))} ·
  source : {e(donnees.get('duree_traitee_min'))} min ·
  transcription : {e(source_transcription)} ·
  coût total ≈ {e(conso.get('total_usd_aux_tarifs_publies'))} $ ·
  scores : {MENTION}</p>
</section>
<section>
 <h2>Les shorts ({len(shorts)})</h2>
 {cartes or "<p class='vide'>aucun short rendu</p>"}
</section>
{_section_ecartes(donnees.get('ecartes') or [])}
<p class="mention" style="text-align:center">MOTEUR DÉCOUPE V1 — tous les
 scores affichés sont des estimations non calibrées.</p>
</main>
<script>{SCRIPT_COPIE}</script>
</body>
</html>"""
    Path(chemin_html).write_text(contenu, encoding="utf-8")
    return chemin_html
