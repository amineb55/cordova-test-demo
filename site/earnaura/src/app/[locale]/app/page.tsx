import Link from "next/link";
import { notFound } from "next/navigation";
import { estLocale } from "@/i18n/config";
import { dictionnaire } from "@/i18n/dictionnaires";
import Analyse from "@/components/Analyse";

// Tranche B — parcours réel : upload → analyse (mode A/B) → rapport.
// Mode fondateur : un seul accès, ni comptes ni paiement (tranche C).
export default async function PageAnalyse({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  if (!estLocale(locale)) notFound();
  const d = dictionnaire(locale);

  return (
    <main className="mx-auto max-w-3xl px-4 py-10 sm:py-14">
      <div className="mb-8 text-center">
        <span className="rounded-full bg-ambre-fonce px-3 py-1 text-xs font-bold uppercase tracking-widest text-ambre">
          {d.analyse.badge}
        </span>
        <h1 className="mt-4 text-3xl font-extrabold">{d.analyse.titre}</h1>
        <p className="mt-2 text-sourdine">{d.analyse.sousTitre}</p>
      </div>

      <Analyse d={d} locale={locale} />

      <p className="mt-10 text-center">
        <Link href={`/${locale}`} className="text-sm text-faible hover:text-encre">
          {d.apercu.retour}
        </Link>
      </p>
    </main>
  );
}
