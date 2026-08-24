#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
COACH IA V1 — assistant conversationnel des créateurs (terminal)
================================================================
Couche de chat au-dessus des moteurs existants, pilotés comme des
outils (function calling). Ne modifie pas moteur.py ni decoupe.py.

Usage :
  python coach.py --profil profil.json

Commandes dans le chat :
  /creer                 le flux « What should I create? »
  /analyser <fichier> [ma-video|inspiration]
  /decouper <fichier> [nb_shorts]
  /rapports              liste des analyses passées
  /cout                  dépense de la session
  /quitter
Tout le reste est de la conversation libre.
"""
import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from dotenv import load_dotenv

import prompts
import reference_ingestion as ri
from moteur import (CHAMPS_FICHE_B, DOSSIER_MODULE, MODELE_TEXTE_DEFAUT,
                    PRIX_ENTREE_PAR_M, PRIX_SORTIE_PAR_M, SCHEMA_PROFIL,
                    THINKING_BUDGET_DEFAUT, TOKENS_VIDEO_PAR_S, Gemini,
                    appel_json, bloc_donnees, erreur_fatale,
                    problemes_dict, slug, traduire_erreur_api)

BUDGET_SESSION_DEFAUT = 0.20      # USD (COACH_BUDGET_SESSION dans .env)
MAX_OUTILS_PAR_TOUR = 3
TOURS_ENVOYES_AU_MODELE = 20
FICHIER_SESSION = DOSSIER_MODULE / "coach_session.json"
COULEUR = {"coach": "\033[96m", "info": "\033[90m", "alerte": "\033[93m",
           "fin": "\033[0m"}


def teinter(texte, ton):
    return f"{COULEUR.get(ton, '')}{texte}{COULEUR['fin']}"


def avec_retry_souple(fonction, nom):
    """Un retry comme partout (brief §8), mais JAMAIS fatal : un échec
    persistant remonte au tour de chat, la session continue."""
    try:
        return fonction()
    except Exception as e:  # noqa: BLE001
        print(teinter(f"  ⚠ {nom} : {traduire_erreur_api(e)} — "
                      "nouvel essai dans 5 s…", "info"))
        time.sleep(5)
        return fonction()


# ----------------------------------------------------------------------
# Déclarations des 6 outils (schémas JSON)
# ----------------------------------------------------------------------
DECLARATIONS_OUTILS = [
    {"name": "generer_fiche",
     "description": "Transforme la vision (idée, sujet, envie — même vague) de "
                    "l'utilisateur en fiche idée complète, adaptée à son profil, "
                    "son pays et son historique.",
     "parameters_json_schema": {
         "type": "object",
         "properties": {
             "vision": {"type": "string",
                        "description": "l'idée ou l'envie exprimée par l'utilisateur"},
             "sujet": {"type": "string",
                       "description": "sujet précis si identifiable (optionnel)"}},
         "required": ["vision"]}},
    {"name": "lire_rapport",
     "description": "Charge un rapport d'analyse existant (rapport.json ou "
                    "decoupe.json dans sorties/) pour répondre avec les vraies "
                    "données : verdict, actions, preuves, formule, fiches.",
     "parameters_json_schema": {
         "type": "object",
         "properties": {
             "chemin": {"type": "string",
                        "description": "nom de la vidéo analysée ou chemin du "
                                       "rapport (ex. « test » ou "
                                       "« sorties/test/rapport.json »)"}},
         "required": ["chemin"]}},
    {"name": "chercher_videos",
     "description": "Recherche de vraies vidéos YouTube (titre, chaîne, lien, "
                    "date). À utiliser pour TOUTE demande de liens ou d'exemples "
                    "de vidéos réelles.",
     "parameters_json_schema": {
         "type": "object",
         "properties": {
             "requete": {"type": "string"},
             "pays": {"type": "string",
                      "description": "pays cible pour régionaliser (optionnel)"}},
         "required": ["requete"]}},
    {"name": "doc_produit",
     "description": "Lit la documentation du produit (modes A/B, Moteur "
                    "Découpe, options CLI, logique des crédits) pour expliquer "
                    "comment utiliser la plateforme.",
     "parameters_json_schema": {"type": "object", "properties": {}}},
    {"name": "analyser_video",
     "description": "Lance une analyse complète d'une vidéo courte locale "
                    "(mode ma-video : diagnostic ; mode inspiration : formule + "
                    "5 fiches). Affiche l'estimation de coût et demande la "
                    "confirmation de l'utilisateur avant tout lancement.",
     "parameters_json_schema": {
         "type": "object",
         "properties": {
             "chemin": {"type": "string", "description": "chemin de la vidéo"},
             "mode": {"type": "string", "enum": ["ma-video", "inspiration"]}},
         "required": ["chemin", "mode"]}},
    {"name": "decouper_video",
     "description": "Lance le Moteur Découpe sur une vidéo longue locale "
                    "appartenant à l'utilisateur (question des droits posée "
                    "systématiquement, estimation + confirmation avant "
                    "lancement).",
     "parameters_json_schema": {
         "type": "object",
         "properties": {
             "chemin": {"type": "string", "description": "chemin de la vidéo"},
             "nb_shorts": {"type": "integer", "minimum": 1, "maximum": 12}},
         "required": ["chemin"]}},
]


# ----------------------------------------------------------------------
# Le Coach
# ----------------------------------------------------------------------
class Coach:
    def __init__(self, chemin_profil, profil, gemini, modele):
        self.chemin_profil = chemin_profil
        self.profil = profil
        self.gemini = gemini
        self.modele = modele
        self.types = gemini._types
        self.cle_youtube = (os.environ.get("YOUTUBE_API_KEY") or "").strip()
        try:
            self.budget_session = float(
                os.environ.get("COACH_BUDGET_SESSION") or BUDGET_SESSION_DEFAUT)
        except ValueError:
            self.budget_session = BUDGET_SESSION_DEFAUT
        self.depense_moteurs_usd = 0.0   # sous-processus moteur/découpe
        self.budget_confirme = False
        self.historique = []             # [{"role","texte"}] pour le modèle
        self.journal = []                # session complète pour coach_session.json
        self.culture = self._charger_culture()
        self.rapports = self._lister_rapports()

    # ---- contexte de démarrage --------------------------------------
    def _charger_culture(self):
        pays = ((self.profil.get("pays_cibles") or [None])[0]
                if isinstance(self.profil.get("pays_cibles"), list)
                else self.profil.get("pays_cibles")) or self.profil.get("pays") or "Maroc"
        cache = DOSSIER_MODULE / "data" / "cultures" / f"{slug(str(pays))}.json"
        if cache.exists():
            try:
                return json.loads(cache.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                pass
        return {"pays": str(pays),
                "note": "profil culturel non encore généré (première analyse à venir)"}

    def _lister_rapports(self):
        rapports = []
        dossier = DOSSIER_MODULE / "sorties"
        if dossier.exists():
            for r in sorted(dossier.glob("*/rapport.json")):
                try:
                    d = json.loads(r.read_text(encoding="utf-8"))
                except (json.JSONDecodeError, OSError):
                    continue
                generation = d.get("generation")
                verdict = generation.get("verdict") if isinstance(generation, dict) else None
                sujets = []
                if isinstance(generation, list):
                    sujets = [f.get("titre_hook") for f in generation
                              if isinstance(f, dict)][:5]
                rapports.append({"nom": r.parent.name, "mode": d.get("mode"),
                                 "date": d.get("genere_le"), "verdict": verdict,
                                 "sujets_fiches": sujets,
                                 "chemin": f"sorties/{r.parent.name}/rapport.json"})
            for r in sorted(dossier.glob("*/shorts/decoupe.json")):
                try:
                    d = json.loads(r.read_text(encoding="utf-8"))
                except (json.JSONDecodeError, OSError):
                    continue
                rapports.append({"nom": r.parent.parent.name, "mode": "decoupe",
                                 "date": d.get("genere_le"),
                                 "verdict": f"{len(d.get('shorts') or [])} shorts",
                                 "sujets_fiches": [],
                                 "chemin": str(r.relative_to(DOSSIER_MODULE))})
        return rapports

    def instruction_systeme(self):
        return (prompts.PROMPT_COACH
                + bloc_donnees("PROFIL CRÉATEUR", self.profil)
                + bloc_donnees("PROFIL CULTUREL DU PAYS CIBLE", self.culture)
                + bloc_donnees("RAPPORTS DISPONIBLES (nom, date, mode, verdict)",
                               self.rapports or "aucun rapport pour l'instant")
                + "\n\nDate du jour : " + datetime.date.today().isoformat())

    # ---- coûts -------------------------------------------------------
    def depense_session_usd(self):
        return self.gemini.cout_reel_usd() + self.depense_moteurs_usd

    def afficher_compteur(self, avant):
        tour = self.depense_session_usd() - avant
        print(teinter(f"   [coût : {tour:.4f} $ ce tour | "
                      f"{self.depense_session_usd():.4f} $ session]", "info"))

    def verifier_budget(self):
        if self.depense_session_usd() <= self.budget_session or self.budget_confirme:
            return True
        print(teinter(f"⚠ Budget de session dépassé : "
                      f"{self.depense_session_usd():.4f} $ > "
                      f"{self.budget_session:.2f} $ (COACH_BUDGET_SESSION).",
                      "alerte"))
        reponse = input("  Continuer quand même ? [o/N] ").strip().lower()
        if reponse in ("o", "oui", "y", "yes"):
            self.budget_confirme = True
            return True
        return False

    # ---- outils ------------------------------------------------------
    def outil_generer_fiche(self, vision, sujet=None):
        historique_sujets = [s for r in self.rapports
                             for s in (r.get("sujets_fiches") or [])]
        prompt = (prompts.PROMPT_VISION_FICHE
                  + bloc_donnees("VISION DE L'UTILISATEUR",
                                 {"vision": vision, "sujet": sujet})
                  + bloc_donnees("SCHÉMA DE FICHE EXISTANT (champs obligatoires)",
                                 [c for c, _ in CHAMPS_FICHE_B] + ["questions_ouvertes"])
                  + bloc_donnees("PROFIL CRÉATEUR", self.profil)
                  + bloc_donnees("PROFIL CULTUREL", self.culture)
                  + bloc_donnees("HISTORIQUE (sujets déjà traités)",
                                 historique_sujets or "aucun"))

        def valider(o):
            pbs = problemes_dict(o, CHAMPS_FICHE_B + [("questions_ouvertes", list)])
            if pbs:
                return pbs
            if len(o["textes_ecran"] or []) < 2:
                pbs.append("« textes_ecran » doit contenir au moins 2 temps")
            return pbs

        def generer_texte(correctif):
            return avec_retry_souple(
                lambda: self.gemini.generer(self.modele, prompt + (correctif or ""),
                                            temperature=0.7),
                "Génération de fiche")

        fiche = appel_json(generer_texte, valider, "Fiche idée")
        self._afficher_fiche(fiche)
        return {"fiche": fiche,
                "note": "La fiche complète et son éventuel warning de "
                        "sensibilité ont déjà été affichés à l'utilisateur — "
                        "présente la raison du choix, réponds aux "
                        "questions_ouvertes s'il y en a, et donne la prochaine "
                        "action."}

    def _afficher_fiche(self, fiche):
        print(teinter("\n┌─ FICHE IDÉE " + "─" * 46, "coach"))
        print(f"│ {fiche.get('titre_hook')}")
        print(f"│ Durée cible : {fiche.get('duree_cible_s')} s — "
              f"score : estimation non calibrée")
        print("│ Script :")
        for ligne in str(fiche.get("script_complet") or "").splitlines():
            print(f"│   {ligne}")
        for t in fiche.get("textes_ecran") or []:
            if isinstance(t, dict):
                print(f"│ Écran {t.get('s')} s : {t.get('texte')}")
        print(f"│ Plan de tournage : {' · '.join(map(str, fiche.get('plan_de_tournage') or []))}")
        print(f"│ CTA : {fiche.get('cta')}")
        print(teinter("└" + "─" * 59, "coach"))
        sensibilite = str(fiche.get("sensibilite_plateforme") or "")
        if sensibilite.split("—")[0].strip().lower() in ("moyenne", "haute") \
                or sensibilite.lower().startswith(("moyenne", "haute")):
            niveau = "ÉLEVÉ" if sensibilite.lower().startswith("haute") else "À VÉRIFIER"
            print(teinter(f"⚠ WARNING CONFORMITÉ — risque {niveau}. "
                          f"Raison : {sensibilite}. Selon les règles publiques "
                          "disponibles à ce jour — un risque, pas un verdict.",
                          "alerte"))
        if fiche.get("actualite_requise"):
            print(teinter("⚠ Actualité requise : vérifier une source fraîche "
                          "avant tournage (voir la fiche).", "alerte"))

    def outil_lire_rapport(self, chemin):
        base = DOSSIER_MODULE / "sorties"
        candidats = [Path(chemin), base / chemin, base / chemin / "rapport.json",
                     base / chemin / "shorts" / "decoupe.json",
                     DOSSIER_MODULE / chemin]
        cible = next((c for c in candidats if c.is_file()), None)
        if cible is None:
            return {"erreur": f"rapport introuvable pour « {chemin} ». "
                              f"Rapports disponibles : "
                              f"{[r['chemin'] for r in self.rapports] or 'aucun'}"}
        try:
            cible.resolve().relative_to(base.resolve())
        except ValueError:
            return {"erreur": "chemin hors du dossier sorties/ — refusé"}
        try:
            d = json.loads(cible.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return {"erreur": "rapport illisible (JSON invalide)"}
        # version condensée : tout sauf les gros tableaux bruts
        for lourd in ("ingestion", "segments", "transcription", "comprehension",
                      "signaux", "candidats"):
            if isinstance(d.get(lourd), (dict, list)):
                if lourd == "ingestion":
                    d[lourd] = {k: v for k, v in d[lourd].items() if k != "timeline"}
                elif lourd == "comprehension":
                    d[lourd] = {k: v for k, v in d[lourd].items() if k != "transcript"}
                else:
                    d.pop(lourd, None)
        texte = json.dumps(d, ensure_ascii=False)
        if len(texte) > 20000:
            texte = texte[:20000] + "… (tronqué)"
        return {"rapport": texte}

    def outil_chercher_videos(self, requete, pays=None):
        if not self.cle_youtube:
            return {"erreur": "recherche indisponible sans clé YouTube",
                    "consigne": "Dis-le tel quel à l'utilisateur et ne fournis "
                                "AUCUN lien de mémoire."}
        codes = {"maroc": "MA", "france": "FR", "algerie": "DZ", "algérie": "DZ",
                 "tunisie": "TN", "egypte": "EG", "égypte": "EG"}
        parametres = {"part": "snippet", "q": requete, "type": "video",
                      "maxResults": "5", "key": self.cle_youtube}
        code = codes.get((pays or "").strip().lower())
        if code:
            parametres["regionCode"] = code
        url = ("https://www.googleapis.com/youtube/v3/search?"
               + urllib.parse.urlencode(parametres))

        def _appel():
            with urllib.request.urlopen(url, timeout=30) as r:
                return json.load(r)

        try:
            donnees = _appel()
        except urllib.error.HTTPError as e:
            corps = e.read().decode("utf-8", "replace")
            if e.code == 403 and "quota" in corps.lower():
                return {"erreur": "quota YouTube dépassé pour aujourd'hui — "
                                  "recherche indisponible, aucun lien à fournir"}
            return {"erreur": f"recherche YouTube en échec (HTTP {e.code}) — "
                              "aucun lien à fournir"}
        except Exception as e:  # noqa: BLE001
            return {"erreur": f"recherche YouTube en échec "
                              f"({traduire_erreur_api(e)}) — aucun lien à fournir"}
        resultats = []
        for item in donnees.get("items", []):
            extrait = item.get("snippet") or {}
            ident = (item.get("id") or {}).get("videoId")
            if not ident:
                continue
            resultats.append({"titre": extrait.get("title"),
                              "chaine": extrait.get("channelTitle"),
                              "lien": f"https://www.youtube.com/watch?v={ident}",
                              "date": extrait.get("publishedAt")})
        return {"resultats": resultats or "aucun résultat"}

    def outil_doc_produit(self):
        chemin = DOSSIER_MODULE / "PRODUIT.md"
        if not chemin.exists():
            return {"erreur": "PRODUIT.md introuvable"}
        return {"documentation": chemin.read_text(encoding="utf-8")}

    # ---- lancement des moteurs (estimation + confirmation) -----------
    def _confirmer_lancement(self, description, cout_estime):
        print(teinter(f"\n▶ {description}", "coach"))
        print(f"  Coût estimé : ~{cout_estime:.3f} $ — estimation non calibrée")
        reponse = input("  Confirmer le lancement ? [o/N] ").strip().lower()
        return reponse in ("o", "oui", "y", "yes")

    def _lancer_sous_processus(self, script, arguments, nom):
        commande = [sys.executable, str(DOSSIER_MODULE / script), *arguments]
        print(teinter(f"  Lancement : {' '.join(commande[1:])}", "info"))
        r = subprocess.run(commande, cwd=DOSSIER_MODULE, capture_output=True,
                           text=True, timeout=3600)
        if r.returncode != 0:
            sortie = (r.stderr or r.stdout).strip()[-400:]
            return None, f"{nom} en échec : {sortie}"
        return r.stdout, None

    def outil_analyser_video(self, chemin, mode):
        video = Path(chemin).expanduser()
        if not video.is_file():
            return {"erreur": f"vidéo introuvable : {chemin}"}
        if mode not in ("ma-video", "inspiration"):
            return {"erreur": "mode invalide (ma-video ou inspiration)"}
        try:
            duree = ri.metadonnees(str(video))["duree_s"]
        except Exception:  # noqa: BLE001
            return {"erreur": "vidéo illisible (format non supporté ?)"}
        if duree > 180:
            return {"erreur": f"vidéo trop longue pour une analyse "
                              f"({duree:.0f} s > 180 s). Pour une vidéo longue, "
                              "c'est le Moteur Découpe qu'il faut."}
        # même base d'estimation que le moteur : vidéo + 3 appels texte + réflexion
        tokens = duree * TOKENS_VIDEO_PAR_S + 6000
        cout = (tokens * PRIX_ENTREE_PAR_M
                + 4 * (1500 + THINKING_BUDGET_DEFAUT) * PRIX_SORTIE_PAR_M) / 1e6
        if not self._confirmer_lancement(
                f"Analyse « {mode} » de {video.name} ({duree:.0f} s)", cout):
            return {"refus": "l'utilisateur n'a pas confirmé le lancement"}
        _, erreur = self._lancer_sous_processus(
            "moteur.py", [str(video), "--mode", mode, "--profil",
                          str(self.chemin_profil), "--oui"], "analyse")
        if erreur:
            return {"erreur": erreur}
        rapport = DOSSIER_MODULE / "sorties" / video.stem / "rapport.json"
        try:
            d = json.loads(rapport.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {"erreur": "analyse terminée mais rapport illisible"}
        conso = (d.get("consommation_gemini") or {})
        self.depense_moteurs_usd += float(conso.get("cout_usd_aux_tarifs_publies") or 0)
        self.rapports = self._lister_rapports()
        generation = d.get("generation")
        resume = {"rapport_html": f"sorties/{video.stem}/rapport.html",
                  "cout_reel_usd": conso.get("cout_usd_aux_tarifs_publies")}
        if isinstance(generation, dict):
            resume["verdict"] = generation.get("verdict")
            resume["actions_prioritaires"] = generation.get("actions_prioritaires")
            resume["hooks_reecrits"] = generation.get("hooks_reecrits")
        elif isinstance(generation, list):
            resume["fiches"] = [{"titre_hook": f.get("titre_hook"),
                                 "sensibilite_plateforme": f.get("sensibilite_plateforme")}
                                for f in generation if isinstance(f, dict)]
        return {"analyse_terminee": resume}

    def outil_decouper_video(self, chemin, nb_shorts=8):
        video = Path(chemin).expanduser()
        if not video.is_file():
            return {"erreur": f"vidéo introuvable : {chemin}"}
        # §5 bis — question des droits, systématique, AVANT tout le reste
        print(teinter("\n⚠ Question droits : cette vidéo est-elle à toi, ou "
                      "as-tu les droits dessus ?", "alerte"))
        reponse = input("  [o/N] ").strip().lower()
        if reponse not in ("o", "oui", "y", "yes"):
            return {"decoupe_refusee":
                    "l'utilisateur n'a pas les droits sur cette vidéo",
                    "consigne": "Affiche un warning droits d'auteur clair : "
                                "republier le contenu d'un autre créateur viole "
                                "les règles des plateformes et le droit "
                                "d'auteur. Propose l'alternative légitime : "
                                "créer SA version du sujet avec des fiches "
                                "idées (mode inspiration ou generer_fiche). "
                                "Ne relance pas la découpe."}
        try:
            duree = ri.metadonnees(str(video))["duree_s"]
        except Exception:  # noqa: BLE001
            return {"erreur": "vidéo illisible (format non supporté ?)"}
        if duree > 90 * 60:
            return {"erreur": f"vidéo trop longue ({duree / 60:.0f} min > 90 min)"}
        minutes = duree / 60
        cout = minutes * 0.006 + 0.10 + int(nb_shorts) * 0.02
        if not self._confirmer_lancement(
                f"Découpe de {video.name} ({minutes:.1f} min) en "
                f"{nb_shorts} shorts", cout):
            return {"refus": "l'utilisateur n'a pas confirmé le lancement"}
        _, erreur = self._lancer_sous_processus(
            "decoupe.py", [str(video), "--profil", str(self.chemin_profil),
                           "--nb-shorts", str(int(nb_shorts)), "--oui"], "découpe")
        if erreur:
            return {"erreur": erreur}
        donnees = DOSSIER_MODULE / "sorties" / video.stem / "shorts" / "decoupe.json"
        try:
            d = json.loads(donnees.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {"erreur": "découpe terminée mais decoupe.json illisible"}
        conso = (d.get("consommation") or {})
        self.depense_moteurs_usd += float(conso.get("total_usd_aux_tarifs_publies") or 0)
        self.rapports = self._lister_rapports()
        return {"decoupe_terminee": {
            "shorts": [{"fichier": s.get("fichier"), "score": s.get("score"),
                        "titre": (s.get("habillage") or {}).get("titre")}
                       for s in d.get("shorts") or []],
            "rapport_html": f"sorties/{video.stem}/shorts/rapport_decoupe.html",
            "cout_reel_usd": conso.get("total_usd_aux_tarifs_publies")}}

    def executer_outil(self, nom, arguments):
        table = {"generer_fiche": self.outil_generer_fiche,
                 "lire_rapport": self.outil_lire_rapport,
                 "chercher_videos": self.outil_chercher_videos,
                 "doc_produit": self.outil_doc_produit,
                 "analyser_video": self.outil_analyser_video,
                 "decouper_video": self.outil_decouper_video}
        fonction = table.get(nom)
        if fonction is None:
            return {"erreur": f"outil inconnu : {nom}"}
        try:
            return fonction(**arguments)
        except TypeError as e:
            return {"erreur": f"arguments invalides pour {nom} : {e}"}
        except SystemExit:
            # une brique du moteur a déclaré un échec fatal (erreur_fatale) :
            # dans le chat, ça devient une simple erreur d'outil (brief §8)
            return {"erreur": f"outil {nom} : échec persistant d'une brique du "
                              "moteur — réessaie dans un moment"}
        except Exception as e:  # noqa: BLE001 — jamais de crash de session
            return {"erreur": f"outil {nom} en échec : {traduire_erreur_api(e)}"}

    # ---- un tour de conversation ------------------------------------
    def _contenus_pour_modele(self, message_utilisateur):
        t = self.types
        contenus = []
        for tour in self.historique[-TOURS_ENVOYES_AU_MODELE:]:
            contenus.append(t.Content(role=tour["role"],
                                      parts=[t.Part(text=tour["texte"])]))
        contenus.append(t.Content(role="user",
                                  parts=[t.Part(text=message_utilisateur)]))
        return contenus

    def repondre(self, message_utilisateur):
        if not self.verifier_budget():
            return "(tour annulé — budget de session non confirmé)"
        t = self.types
        config = t.GenerateContentConfig(
            system_instruction=self.instruction_systeme(),
            tools=[t.Tool(function_declarations=DECLARATIONS_OUTILS)],
            temperature=0.7,
            thinking_config=t.ThinkingConfig(
                thinking_budget=self.gemini.thinking_budget))
        contenus = self._contenus_pour_modele(message_utilisateur)
        outils_executes = 0
        texte_final = None
        for _ in range(MAX_OUTILS_PAR_TOUR + 2):
            reponse = avec_retry_souple(
                lambda: self.gemini.client.models.generate_content(
                    model=self.modele, contents=contenus, config=config),
                "Coach")
            usage = getattr(reponse, "usage_metadata", None)
            if usage:
                self.gemini.conso["entree"] += usage.prompt_token_count or 0
                self.gemini.conso["sortie"] += usage.candidates_token_count or 0
                self.gemini.conso["reflexion"] += usage.thoughts_token_count or 0
                self.gemini.conso["appels"] += 1
            appels = reponse.function_calls or []
            if not appels:
                texte_final = reponse.text or "(réponse vide)"
                break
            contenus.append(reponse.candidates[0].content)
            parts_reponses = []
            for fc in appels:
                if outils_executes >= MAX_OUTILS_PAR_TOUR:
                    resultat = {"erreur": "limite de 3 outils par tour atteinte "
                                          "— réponds avec ce que tu as déjà"}
                else:
                    arguments = dict(fc.args or {})
                    print(teinter(f"   [outil : {fc.name}"
                                  f"({json.dumps(arguments, ensure_ascii=False)[:90]})]",
                                  "info"))
                    resultat = self.executer_outil(fc.name, arguments)
                    outils_executes += 1
                parts_reponses.append(t.Part.from_function_response(
                    name=fc.name, response={"resultat": resultat}))
            contenus.append(t.Content(role="user", parts=parts_reponses))
        if texte_final is None:
            texte_final = ("Je n'ai pas réussi à conclure ce tour (trop d'appels "
                           "d'outils). Reformule ou découpe ta demande.")
        self.historique.append({"role": "user", "texte": message_utilisateur})
        self.historique.append({"role": "model", "texte": texte_final})
        self.journal.append({"quand": datetime.datetime.now().isoformat(timespec="seconds"),
                             "utilisateur": message_utilisateur,
                             "coach": texte_final,
                             "outils": outils_executes})
        self.sauver_session()
        return texte_final

    # ---- session -----------------------------------------------------
    def charger_session(self):
        if FICHIER_SESSION.exists():
            try:
                donnees = json.loads(FICHIER_SESSION.read_text(encoding="utf-8"))
                for tour in donnees.get("tours", []):
                    self.historique.append({"role": "user",
                                            "texte": tour["utilisateur"]})
                    self.historique.append({"role": "model", "texte": tour["coach"]})
                self.journal = donnees.get("tours", [])
                if self.journal:
                    print(teinter(f"(session précédente rechargée : "
                                  f"{len(self.journal)} tours)", "info"))
            except (json.JSONDecodeError, KeyError):
                print(teinter("(coach_session.json illisible — session neuve)", "info"))

    def sauver_session(self):
        FICHIER_SESSION.write_text(
            json.dumps({"profil": str(self.chemin_profil),
                        "tours": self.journal[-200:]},
                       ensure_ascii=False, indent=1), encoding="utf-8")


# ----------------------------------------------------------------------
# Commandes locales
# ----------------------------------------------------------------------
def commande_rapports(coach):
    if not coach.rapports:
        print("Aucune analyse pour l'instant. Lance-en une : "
              "python moteur.py <video> --mode ma-video --profil profil.json")
        return
    print("Analyses disponibles :")
    for r in coach.rapports:
        print(f"  • {r['nom']} — {r['mode']} — {r['date']} — "
              f"verdict : {r['verdict'] or '—'}")


def commande_cout(coach):
    c = coach.gemini.conso
    print(f"Dépense de la session : {coach.depense_session_usd():.4f} $ "
          f"(plafond {coach.budget_session:.2f} $)")
    print(f"  Chat : {c['appels']} appel(s), {c['entree']} tokens entrée, "
          f"{c['sortie']} sortie, {c['reflexion']} réflexion "
          f"→ {coach.gemini.cout_reel_usd():.4f} $")
    print(f"  Moteurs lancés : {coach.depense_moteurs_usd:.4f} $")
    print("  (tarifs publiés, estimation non calibrée)")


def message_creer(coach):
    contexte = {"rapports": coach.rapports,
                "sujets_deja_traites": [s for r in coach.rapports
                                        for s in (r.get("sujets_fiches") or [])]}
    return ("[COMMANDE /creer — flux « What should I create? ». Séquence "
            "OBLIGATOIRE : 1) appuie-toi sur le profil et l'historique "
            "ci-dessous (s'il n'y a aucun historique, dis-le en une phrase et "
            "travaille à partir du profil seul) ; 2) pose AU MAXIMUM 2 "
            "questions, et seulement si vraiment nécessaires — si le profil "
            "suffit, zéro question ; 3) livre UNE fiche recommandée complète "
            "via l'outil generer_fiche + la raison du choix en 2 phrases + 2 "
            "alternatives en une ligne chacune. Jamais un sujet déjà traité "
            "sauf angle réellement neuf.]"
            + bloc_donnees("HISTORIQUE", contexte))


def main():
    parseur = argparse.ArgumentParser(description="COACH IA V1 — chat créateur")
    parseur.add_argument("--profil", required=True, help="profil créateur (json)")
    args = parseur.parse_args()

    load_dotenv(DOSSIER_MODULE / ".env")
    load_dotenv()
    cle_google = (os.environ.get("GOOGLE_API_KEY") or "").strip()
    if not cle_google:
        erreur_fatale("GOOGLE_API_KEY manquante (obligatoire). Copiez "
                      ".env.example vers .env puis renseignez votre clé.")
    chemin_profil = Path(args.profil).resolve()
    if not chemin_profil.exists():
        erreur_fatale(f"fichier profil introuvable : {chemin_profil}")
    try:
        profil = json.loads(chemin_profil.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        erreur_fatale(f"profil.json invalide : {e}")
    pbs = problemes_dict(profil, SCHEMA_PROFIL)
    if pbs:
        erreur_fatale("le profil créateur ne respecte pas le schéma attendu ("
                      + " ; ".join(pbs) + ").")
    modele = (os.environ.get("GEMINI_MODELE_TEXTE") or MODELE_TEXTE_DEFAUT).strip()
    brut_budget = (os.environ.get("GEMINI_THINKING_BUDGET") or "").strip()
    thinking = THINKING_BUDGET_DEFAUT
    if brut_budget:
        try:
            thinking = max(int(brut_budget), -1)
        except ValueError:
            erreur_fatale("GEMINI_THINKING_BUDGET invalide (entier attendu).")

    coach = Coach(chemin_profil, profil, Gemini(cle_google, thinking), modele)
    coach.charger_session()

    print(teinter("\n═══ COACH — ton assistant créateur ═══", "coach"))
    print("Commandes : /creer  /analyser <fichier> [mode]  /decouper <fichier> "
          "[nb]  /rapports  /cout  /quitter — sinon, parle-moi normalement.")
    if not coach.cle_youtube:
        print(teinter("(YOUTUBE_API_KEY absente : la recherche de vidéos "
                      "répondra « indisponible » — aucun lien ne sera inventé)",
                      "info"))
    print()

    while True:
        try:
            entree = input("toi > ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nÀ bientôt.")
            break
        if not entree:
            continue
        avant = coach.depense_session_usd()
        try:
            if entree in ("/quitter", "/exit", "/q"):
                print("À bientôt.")
                break
            if entree == "/rapports":
                commande_rapports(coach)
                continue
            if entree == "/cout":
                commande_cout(coach)
                continue
            if entree == "/creer":
                message = message_creer(coach)
            elif entree.startswith("/analyser"):
                morceaux = entree.split()
                if len(morceaux) < 2:
                    print("Usage : /analyser <fichier> [ma-video|inspiration]")
                    continue
                mode = morceaux[2] if len(morceaux) > 2 else "ma-video"
                message = (f"[COMMANDE /analyser — lance l'outil analyser_video "
                           f"sur « {morceaux[1]} » en mode « {mode} », puis "
                           "résume le verdict et les actions du rapport.]")
            elif entree.startswith("/decouper"):
                morceaux = entree.split()
                if len(morceaux) < 2:
                    print("Usage : /decouper <fichier> [nb_shorts]")
                    continue
                nb = morceaux[2] if len(morceaux) > 2 else "8"
                message = (f"[COMMANDE /decouper — lance l'outil decouper_video "
                           f"sur « {morceaux[1]} » ({nb} shorts). La question "
                           "des droits sera posée par l'outil ; respecte sa "
                           "réponse.]")
            elif entree.startswith("/"):
                print("Commande inconnue. Commandes : /creer /analyser "
                      "/decouper /rapports /cout /quitter")
                continue
            else:
                message = entree
            reponse = coach.repondre(message)
            print(f"\n{teinter('coach >', 'coach')} {reponse}\n")
            coach.afficher_compteur(avant)
        except SystemExit:
            raise
        except Exception as e:  # noqa: BLE001 — jamais de crash de session
            print(teinter(f"⚠ Petit souci ce tour ({traduire_erreur_api(e)}) — "
                          "on continue, reformule si besoin.", "alerte"))


if __name__ == "__main__":
    main()
