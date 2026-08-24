"use client";

import { useState } from "react";
import Link from "next/link";
import type { Dictionnaire } from "@/i18n/dictionnaires";
import type { Locale } from "@/i18n/config";

type Zone = "maghreb" | "intl";

export default function Tarifs({ d, locale }: { d: Dictionnaire; locale: Locale }) {
  const [zone, setZone] = useState<Zone>("maghreb");
  const t = d.tarifs;

  const plans = [
    {
      nom: t.gratuitNom,
      prix: t.gratuitPrix,
      suffixe: "",
      note: "",
      points: t.gratuitPts,
      populaire: false,
    },
    {
      nom: t.proNom,
      prix: zone === "maghreb" ? t.prixPro.maghreb : t.prixPro.intl,
      suffixe: t.parMois,
      note: zone === "maghreb" ? t.creditsPro.maghreb : t.creditsPro.intl,
      points: t.proPts,
      populaire: true,
    },
    {
      nom: t.creatorNom,
      prix: zone === "maghreb" ? t.prixCreator.maghreb : t.prixCreator.intl,
      suffixe: t.parMois,
      note: "",
      points: t.creatorPts,
      populaire: false,
    },
    {
      nom: t.studioNom,
      prix: t.studioPrix,
      suffixe: t.parMois,
      note: t.studioNote,
      points: t.studioPts,
      populaire: false,
    },
  ];

  return (
    <section id="tarifs" className="mx-auto max-w-5xl px-4 py-16">
      <h2 className="text-center text-2xl font-bold sm:text-3xl">{t.titre}</h2>
      <p className="mt-2 text-center text-sourdine">{t.sousTitre}</p>

      <div className="mt-6 flex justify-center">
        <div className="inline-flex rounded-full border border-bord bg-carte p-1 text-sm">
          {(
            [
              ["maghreb", t.zoneMaghreb],
              ["intl", t.zoneIntl],
            ] as const
          ).map(([valeur, libelle]) => (
            <button
              key={valeur}
              onClick={() => setZone(valeur)}
              className={`rounded-full px-4 py-1.5 font-semibold transition ${
                zone === valeur ? "bg-accent text-fond" : "text-sourdine hover:text-encre"
              }`}
              aria-pressed={zone === valeur}
            >
              {libelle}
            </button>
          ))}
        </div>
      </div>

      <div className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {plans.map((plan) => (
          <div
            key={plan.nom}
            className={`flex flex-col rounded-2xl border bg-surface p-5 ${
              plan.populaire ? "border-accent/60" : "border-bord"
            }`}
          >
            {plan.populaire ? (
              <span className="mb-2 self-start rounded-full bg-accent-fonce px-2.5 py-0.5 text-xs font-bold text-accent">
                {t.populaire}
              </span>
            ) : (
              <span className="mb-2 h-5" aria-hidden />
            )}
            <h3 className="text-lg font-bold">{plan.nom}</h3>
            <p className="mt-1">
              <span className="text-2xl font-extrabold tabular-nums">{plan.prix}</span>
              <span className="text-sm text-faible">{plan.suffixe}</span>
            </p>
            {plan.note && <p className="text-xs text-bleu">{plan.note}</p>}
            <ul className="mt-4 flex-1 space-y-2 text-sm text-sourdine">
              {plan.points.map((point) => (
                <li key={point} className="flex gap-2">
                  <span className="text-accent" aria-hidden>
                    ✓
                  </span>
                  {point}
                </li>
              ))}
            </ul>
            <Link
              href={`/${locale}/app`}
              className={`mt-5 rounded-full py-2 text-center text-sm font-bold ${
                plan.populaire
                  ? "bg-accent text-fond hover:opacity-90"
                  : "border border-bord text-encre hover:border-faible"
              }`}
            >
              {t.cta}
            </Link>
          </div>
        ))}
      </div>
      <p className="mt-4 text-center text-xs text-faible">{t.mention}</p>
    </section>
  );
}
