import Link from "next/link";
import { notFound } from "next/navigation";
import { estLocale } from "@/i18n/config";
import { dictionnaire } from "@/i18n/dictionnaires";

// Tranche A : page d'attente honnête — le parcours upload → analyse →
// rapport ouvre avec la tranche B.
export default async function Apercu({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  if (!estLocale(locale)) notFound();
  const d = dictionnaire(locale);

  return (
    <main className="mx-auto flex max-w-2xl flex-col items-center px-4 py-28 text-center">
      <span className="rounded-full bg-ambre-fonce px-3 py-1 text-xs font-bold uppercase tracking-widest text-ambre">
        {d.apercu.badge}
      </span>
      <h1 className="mt-6 text-3xl font-extrabold">{d.apercu.titre}</h1>
      <p className="mt-4 text-sourdine">{d.apercu.texte}</p>
      <Link
        href={`/${locale}`}
        className="mt-8 rounded-full border border-bord px-6 py-2.5 font-semibold text-sourdine hover:border-faible hover:text-encre"
      >
        {d.apercu.retour}
      </Link>
    </main>
  );
}
