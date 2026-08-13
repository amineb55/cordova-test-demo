# -*- coding: utf-8 -*-
"""
Génération de rapport.html — une seule page autonome (HTML + CSS inline,
aucun framework), fond sombre, lisible sur mobile (brief §7).
Sections dans l'ordre : verdict — actions prioritaires (mode A) ou les
5 fiches idées (mode B) — graphique timeline — formule décodée —
transcript replié.
"""
import base64
import html
from pathlib import Path

MENTION = "estimation non calibrée"


def e(valeur):
    """Échappe tout contenu venant des modèles avant insertion HTML."""
    if valeur is None:
        return "—"
    return html.escape(str(valeur))


def fmt_s(valeur):
    try:
        return f"{float(valeur):.1f} s"
    except (TypeError, ValueError):
        return "—"


def _liste(valeurs):
    if not valeurs:
        return "<p class='vide'>—</p>"
    items = "".join(f"<li>{e(v)}</li>" for v in valeurs)
    return f"<ul>{items}</ul>"


def _textes_ecran(textes):
    if not textes:
        return "<p class='vide'>—</p>"
    lignes = "".join(
        f"<div class='ecran'><span class='ts'>{fmt_s(t.get('s'))}</span>"
        f"<span class='ecran-texte'>{e(t.get('texte'))}</span></div>"
        for t in textes if isinstance(t, dict))
    return lignes or "<p class='vide'>—</p>"


# ----------------------------------------------------------------------
def _section_verdict(generation):
    verdict = generation.get("verdict", "?")
    libelles = {"pret_a_publier": ("Prêt à publier", "vert"),
                "optimiser_avant": ("Optimiser avant de publier", "orange"),
                "revoir": ("À revoir", "rouge")}
    libelle, classe = libelles.get(verdict, (verdict, "orange"))
    return f"""
<section>
 <h2>Verdict</h2>
 <div class="verdict {classe}">{e(libelle)}</div>
 <p class="mention">{MENTION}</p>
</section>"""


def _section_actions(generation):
    blocs = []
    for a in generation.get("actions_prioritaires") or []:
        if not isinstance(a, dict):
            continue
        prio = str(a.get("priorite", "IMPORTANT")).upper()
        classe = "rouge" if "CRIT" in prio else "orange"
        blocs.append(f"""
 <div class="carte action">
  <div class="entete-action"><span class="badge {classe}">{e(prio)}</span>
   <span class="ts">{fmt_s(a.get('s'))}</span></div>
  <p class="action-titre">{e(a.get('action'))}</p>
  <p><strong>Pourquoi :</strong> {e(a.get('justification'))}</p>
  <p class="preuve"><strong>Preuve :</strong> {e(a.get('preuve'))}</p>
 </div>""")
    hooks = _liste(generation.get("hooks_reecrits"))
    ecrans = _textes_ecran(generation.get("texte_ecran_2_temps"))
    return f"""
<section>
 <h2>Actions prioritaires</h2>
 {''.join(blocs) or "<p class='vide'>—</p>"}
 <h3>3 hooks réécrits (langue de la vidéo)</h3>
 {hooks}
 <h3>Texte à l'écran « en 2 temps »</h3>
 {ecrans}
 <h3>CTA de fin</h3>
 <p class="cta">{e(generation.get('cta_fin'))}</p>
</section>"""


def _section_fiches(generation):
    fiches = generation if isinstance(generation, list) else []
    cartes = []
    for i, f in enumerate(fiches, 1):
        if not isinstance(f, dict):
            continue
        actu = ""
        if f.get("actualite_requise"):
            actu = ("<p class='alerte'>⚠ Actualité requise — vérifier une "
                    "source fraîche avant tournage (voir script).</p>")
        cartes.append(f"""
 <div class="carte fiche">
  <div class="fiche-num">Fiche {i}</div>
  <h3 class="titre-hook">{e(f.get('titre_hook'))}</h3>
  <p><strong>Pourquoi ça marche :</strong> {e(f.get('pourquoi_ca_marche'))}</p>
  <details><summary>Script complet</summary>
   <p class="script">{e(f.get('script_complet'))}</p></details>
  <h4>Textes à l'écran</h4>
  {_textes_ecran(f.get('textes_ecran'))}
  <h4>Plan de tournage</h4>
  {_liste(f.get('plan_de_tournage'))}
  <p><strong>CTA :</strong> {e(f.get('cta'))}</p>
  <p><strong>Durée cible :</strong> {fmt_s(f.get('duree_cible_s'))}
   &nbsp;·&nbsp; <strong>Sensibilité plateforme :</strong>
   {e(f.get('sensibilite_plateforme'))} <span class="mention">({MENTION})</span></p>
  {actu}
 </div>""")
    return f"""
<section>
 <h2>Les 5 fiches idées</h2>
 {''.join(cartes) or "<p class='vide'>—</p>"}
</section>"""


def _section_timeline(rapport, png_base64):
    ing = rapport.get("ingestion") or {}
    loud = ing.get("loudness") or {}
    puces = []
    if loud.get("lufs_integre") is not None:
        puces.append(f"Loudness : {e(loud['lufs_integre'])} LUFS "
                     "(cible plateformes ≈ −14)")
    if ing.get("visage_present_pct") is not None:
        puces.append(f"Visage présent : {e(ing['visage_present_pct'])} % du temps")
    puces.append(f"Cuts : {len(ing.get('cuts_s') or [])}")
    puces.append(f"Zones plates (risque de décrochage) : "
                 f"{len(ing.get('zones_plates') or [])}")
    puces.append(f"Silences : {len(ing.get('silences') or [])}")
    chips = "".join(f"<span class='chip'>{p}</span>" for p in puces)
    image = (f"<img alt='Timeline d’attention' "
             f"src='data:image/png;base64,{png_base64}'>" if png_base64
             else "<p class='vide'>graphique indisponible</p>")
    return f"""
<section>
 <h2>Timeline d'attention</h2>
 {image}
 <div class="chips">{chips}</div>
 <p class="mention">mesures automatiques — {MENTION}</p>
</section>"""


def _section_formule(formule):
    hook = formule.get("hook") or {}
    actes = []
    for s in formule.get("structure") or []:
        if not isinstance(s, dict):
            continue
        actes.append(f"<tr><td class='acte'>{e(s.get('acte'))}</td>"
                     f"<td class='ts'>{fmt_s(s.get('de_s'))} → {fmt_s(s.get('a_s'))}</td>"
                     f"<td>{e(s.get('contenu'))}</td></tr>")
    faiblesses = []
    for f in formule.get("faiblesses_observees") or []:
        if not isinstance(f, dict):
            continue
        faiblesses.append(f"<li><span class='ts'>{fmt_s(f.get('s'))}</span> "
                          f"{e(f.get('quoi'))} — <em>preuve : {e(f.get('preuve'))}</em></li>")
    return f"""
<section>
 <h2>Formule décodée</h2>
 <p><strong>Format :</strong> {e(formule.get('format'))}</p>
 <div class="carte">
  <p><strong>Hook</strong> ({e(hook.get('type'))}) : {e(hook.get('texte_ou_action'))}</p>
  <p class="preuve">Pourquoi il accroche : {e(hook.get('pourquoi_il_accroche'))}</p>
 </div>
 <h3>Structure</h3>
 <div class="defilement"><table>
  <tr><th>Acte</th><th>Quand</th><th>Contenu</th></tr>
  {''.join(actes) or "<tr><td colspan='3'>—</td></tr>"}
 </table></div>
 <p><strong>Déclencheur émotionnel :</strong> {e(formule.get('declencheur_emotionnel'))}</p>
 <p><strong>Déclencheur de partage :</strong> {e(formule.get('declencheur_de_partage'))}</p>
 <h3>Éléments transférables (le mécanisme)</h3>
 {_liste(formule.get('elements_transferables'))}
 <h3>Éléments liés au sujet</h3>
 {_liste(formule.get('elements_lies_au_sujet'))}
 <h3>Faiblesses observées</h3>
 <ul>{''.join(faiblesses) or '<li>—</li>'}</ul>
 <p><strong>Pourquoi ça marche :</strong> {e(formule.get('pourquoi_ca_marche'))}</p>
</section>"""


def _section_transcript(rapport):
    transcription = rapport.get("transcription")
    comprehension = rapport.get("comprehension") or {}
    lignes = []
    if transcription and transcription.get("segments"):
        for s in transcription["segments"]:
            lignes.append(f"<div class='ecran'><span class='ts'>"
                          f"{fmt_s(s.get('de_s'))}</span>"
                          f"<span class='ecran-texte'>{e(s.get('texte'))}</span></div>")
        source = "Whisper (horodaté)"
    elif comprehension.get("transcript"):
        lignes.append(f"<p class='script'>{e(comprehension['transcript'])}</p>")
        source = "Gemini"
    else:
        lignes.append("<p class='vide'>aucun transcript disponible</p>")
        source = "—"
    return f"""
<section>
 <details>
  <summary><h2 class="h2-inline">Transcript (source : {e(source)})</h2></summary>
  {''.join(lignes)}
 </details>
</section>"""


# ----------------------------------------------------------------------
def generer_rapport_html(rapport, chemin_png, chemin_html):
    png_base64 = ""
    chemin_png = Path(chemin_png)
    if chemin_png.exists():
        png_base64 = base64.b64encode(chemin_png.read_bytes()).decode("ascii")

    mode = rapport.get("mode", "?")
    generation = rapport.get("generation") or {}
    comprehension = rapport.get("comprehension") or {}
    ingestion = rapport.get("ingestion") or {}
    langues = ", ".join(str(x) for x in (comprehension.get("langues_detectees") or [])
                        if x) or "—"

    if mode == "ma-video":
        corps_generation = _section_verdict(generation) + _section_actions(generation)
        sous_titre = "Diagnostic de ma vidéo (mode A)"
    else:
        corps_generation = _section_fiches(generation)
        sous_titre = "Inspiration — formule + 5 fiches idées (mode B)"

    contenu = f"""<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rapport — {e(rapport.get('video'))}</title>
<style>
 :root {{ color-scheme: dark; }}
 * {{ box-sizing: border-box; }}
 body {{ margin: 0; padding: 16px; background: #0f1220; color: #e8eaf6;
        font: 16px/1.55 -apple-system, "Segoe UI", Roboto, sans-serif; }}
 main {{ max-width: 720px; margin: 0 auto; }}
 h1 {{ font-size: 1.35rem; margin: 0 0 2px; color: #ffffff; }}
 h2 {{ font-size: 1.15rem; margin: 0 0 12px; color: #5ee6a8;
      text-transform: uppercase; letter-spacing: .06em; }}
 .h2-inline {{ display: inline; }}
 h3 {{ font-size: 1rem; color: #aab0d0; margin: 18px 0 8px; }}
 h4 {{ font-size: .9rem; color: #aab0d0; margin: 12px 0 6px; }}
 p {{ margin: 6px 0; }}
 section {{ background: #171b30; border: 1px solid #2a2f4d;
           border-radius: 14px; padding: 18px; margin: 14px 0; }}
 .sous-titre {{ color: #aab0d0; margin: 0 0 4px; }}
 .meta {{ color: #8890b5; font-size: .85rem; }}
 .verdict {{ font-size: 1.3rem; font-weight: 700; padding: 12px 16px;
            border-radius: 10px; display: inline-block; }}
 .verdict.vert {{ background: #123726; color: #5ee6a8; }}
 .verdict.orange {{ background: #3a2b12; color: #ffc46b; }}
 .verdict.rouge {{ background: #3a1519; color: #ff8a8a; }}
 .badge {{ font-size: .75rem; font-weight: 700; padding: 3px 8px;
          border-radius: 6px; letter-spacing: .04em; }}
 .badge.rouge {{ background: #3a1519; color: #ff8a8a; }}
 .badge.orange {{ background: #3a2b12; color: #ffc46b; }}
 .carte {{ background: #10142a; border: 1px solid #2a2f4d;
          border-radius: 10px; padding: 14px; margin: 10px 0; }}
 .entete-action {{ display: flex; gap: 10px; align-items: center;
                  margin-bottom: 6px; }}
 .action-titre {{ font-weight: 600; color: #ffffff; }}
 .preuve {{ color: #aab0d0; font-size: .92rem; }}
 .ts {{ color: #7aa8ff; font-variant-numeric: tabular-nums;
       font-size: .88rem; white-space: nowrap; }}
 .ecran {{ display: flex; gap: 12px; padding: 6px 0;
          border-bottom: 1px dashed #2a2f4d; }}
 .ecran-texte {{ flex: 1; }}
 .cta {{ font-weight: 600; color: #5ee6a8; }}
 .fiche-num {{ color: #7aa8ff; font-size: .8rem; font-weight: 700;
              letter-spacing: .08em; text-transform: uppercase; }}
 .titre-hook {{ font-size: 1.25rem; color: #ffffff; margin: 4px 0 10px; }}
 .script {{ white-space: pre-wrap; }}
 .alerte {{ background: #3a2b12; color: #ffc46b; padding: 8px 12px;
           border-radius: 8px; }}
 .mention {{ color: #8890b5; font-size: .78rem; font-style: italic; }}
 .vide {{ color: #8890b5; }}
 img {{ max-width: 100%; border-radius: 10px; display: block; }}
 .chips {{ display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }}
 .chip {{ background: #10142a; border: 1px solid #2a2f4d; padding: 5px 10px;
         border-radius: 999px; font-size: .82rem; color: #aab0d0; }}
 .defilement {{ overflow-x: auto; }}
 table {{ border-collapse: collapse; width: 100%; font-size: .92rem; }}
 th, td {{ text-align: left; padding: 8px 10px;
          border-bottom: 1px solid #2a2f4d; vertical-align: top; }}
 th {{ color: #aab0d0; }}
 .acte {{ text-transform: uppercase; color: #5ee6a8; font-size: .8rem;
         font-weight: 700; }}
 details > summary {{ cursor: pointer; }}
 ul {{ margin: 6px 0; padding-left: 22px; }}
 li {{ margin: 4px 0; }}
</style>
</head>
<body>
<main>
<section>
 <h1>{e(rapport.get('video'))}</h1>
 <p class="sous-titre">{e(sous_titre)}</p>
 <p class="meta">Généré le {e(rapport.get('genere_le'))} ·
  durée {fmt_s(ingestion.get('duree_s'))} · langue(s) : {e(langues)} ·
  modèles : {e((rapport.get('modeles') or {}).get('video'))}</p>
</section>
{corps_generation}
{_section_timeline(rapport, png_base64)}
{_section_formule(rapport.get('formule') or {})}
{_section_transcript(rapport)}
<p class="mention" style="text-align:center">MOTEUR V1 — toutes les
 évaluations affichées sont des estimations non calibrées.</p>
</main>
</body>
</html>"""
    Path(chemin_html).write_text(contenu, encoding="utf-8")
    return chemin_html
