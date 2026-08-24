import Link from "next/link";
import { notFound } from "next/navigation";
import { estLocale } from "@/i18n/config";
import { dictionnaire } from "@/i18n/dictionnaires";
import Tarifs from "@/components/Tarifs";

export default async function Accueil({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  if (!estLocale(locale)) notFound();
  const d = dictionnaire(locale);

  return (
    <main>
      {/* ——— Hero ——— */}
      <section className="halo">
        <div className="mx-auto max-w-5xl px-4 pb-16 pt-20 text-center sm:pt-28">
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-bleu">
            {d.hero.eyebrow}
          </p>
          <h1 className="mx-auto mt-4 max-w-3xl text-balance text-4xl font-extrabold leading-tight tracking-tight sm:text-5xl">
            {d.hero.titre1}
            <br />
            <span className="text-accent">{d.hero.titre2}</span>
            <br />
            {d.hero.titre3}
          </h1>
          <p className="mx-auto mt-6 max-w-2xl text-pretty text-sourdine">
            {d.hero.sousTitre}
          </p>
          <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Link
              href={`/${locale}/app`}
              className="rounded-full bg-accent px-7 py-3 text-base font-bold text-fond hover:opacity-90"
            >
              {d.hero.ctaPrincipal}
            </Link>
            <a
              href="#tarifs"
              className="rounded-full border border-bord px-7 py-3 text-base font-semibold text-sourdine hover:border-faible hover:text-encre"
            >
              {d.hero.ctaSecondaire}
            </a>
          </div>
          <p className="mt-5 text-sm text-faible">{d.hero.honnete}</p>
        </div>
      </section>

      {/* ——— Comment ça marche ——— */}
      <section className="mx-auto max-w-5xl px-4 py-16">
        <h2 className="text-center text-2xl font-bold sm:text-3xl">{d.etapes.titre}</h2>
        <div className="mt-8 grid gap-4 sm:grid-cols-3">
          {(
            [
              ["1", d.etapes.e1t, d.etapes.e1d],
              ["2", d.etapes.e2t, d.etapes.e2d],
              ["3", d.etapes.e3t, d.etapes.e3d],
            ] as const
          ).map(([numero, titre, description]) => (
            <div key={numero} className="rounded-2xl border border-bord bg-surface p-6">
              <span className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-bleu-fonce font-mono text-sm font-bold text-bleu">
                {numero}
              </span>
              <h3 className="mt-4 text-lg font-bold">{titre}</h3>
              <p className="mt-2 text-sm leading-relaxed text-sourdine">{description}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ——— Les fiches idées (exemple flouté) ——— */}
      <section className="mx-auto max-w-5xl px-4 py-16">
        <div className="grid items-center gap-10 lg:grid-cols-2">
          <div>
            <h2 className="text-2xl font-bold sm:text-3xl">{d.fiches.titre}</h2>
            <p className="mt-4 leading-relaxed text-sourdine">{d.fiches.sousTitre}</p>
          </div>
          <div className="relative overflow-hidden rounded-2xl border border-bord bg-surface p-6">
            <p className="text-xs font-bold uppercase tracking-widest text-bleu">
              {d.fiches.exempleType}
            </p>
            <h3 className="mt-2 text-xl font-bold" dir="rtl" lang="ar">
              {d.fiches.exempleTitre}
            </h3>
            <p className="mt-1 text-xs text-faible">
              {d.fiches.exempleScore} · {d.fiches.duree}
            </p>
            <div className="relative mt-5 space-y-4">
              <div aria-hidden className="select-none space-y-4 blur-[7px]">
                <div>
                  <p className="text-xs font-bold uppercase text-faible">
                    {d.fiches.scriptLabel}
                  </p>
                  <div className="mt-2 space-y-2">
                    <div className="h-3 w-11/12 rounded bg-bord" />
                    <div className="h-3 w-full rounded bg-bord" />
                    <div className="h-3 w-9/12 rounded bg-bord" />
                    <div className="h-3 w-10/12 rounded bg-bord" />
                  </div>
                </div>
                <div>
                  <p className="text-xs font-bold uppercase text-faible">
                    {d.fiches.planLabel}
                  </p>
                  <div className="mt-2 space-y-2">
                    <div className="h-3 w-8/12 rounded bg-bord" />
                    <div className="h-3 w-10/12 rounded bg-bord" />
                  </div>
                </div>
              </div>
              <div className="absolute inset-0 flex items-center justify-center">
                <Link
                  href={`/${locale}/app`}
                  className="rounded-full bg-accent px-5 py-2.5 text-sm font-bold text-fond shadow-lg hover:opacity-90"
                >
                  🔒 {d.fiches.ctaFlou}
                </Link>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ——— Sécurité plateformes ——— */}
      <section className="border-y border-bord/70 bg-surface/40">
        <div className="mx-auto max-w-5xl px-4 py-16">
          <h2 className="text-center text-2xl font-bold sm:text-3xl">
            {d.securite.titre}
          </h2>
          <p className="mx-auto mt-2 max-w-2xl text-center text-sourdine">
            {d.securite.sousTitre}
          </p>
          <div className="mt-8 grid gap-4 sm:grid-cols-3">
            {(
              [
                ["⚠️", d.securite.w1t, d.securite.w1d],
                ["🔒", d.securite.w2t, d.securite.w2d],
                ["📏", d.securite.w3t, d.securite.w3d],
              ] as const
            ).map(([icone, titre, description]) => (
              <div key={titre} className="rounded-2xl border border-bord bg-carte p-6">
                <span aria-hidden className="text-xl">
                  {icone}
                </span>
                <h3 className="mt-3 font-bold">{titre}</h3>
                <p className="mt-2 text-sm leading-relaxed text-sourdine">{description}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ——— Tarifs ——— */}
      <Tarifs d={d} locale={locale} />

      {/* ——— FAQ ——— */}
      <section id="faq" className="mx-auto max-w-3xl px-4 py-16">
        <h2 className="text-center text-2xl font-bold sm:text-3xl">{d.faq.titre}</h2>
        <div className="mt-8 space-y-3">
          {(
            [
              [d.faq.q1, d.faq.r1],
              [d.faq.q2, d.faq.r2],
              [d.faq.q3, d.faq.r3],
              [d.faq.q4, d.faq.r4],
              [d.faq.q5, d.faq.r5],
              [d.faq.q6, d.faq.r6],
            ] as const
          ).map(([question, reponse]) => (
            <details
              key={question}
              className="group rounded-xl border border-bord bg-surface px-5 py-4"
            >
              <summary className="cursor-pointer list-none font-semibold marker:hidden">
                <span className="me-2 text-accent transition group-open:rotate-90 inline-block">
                  ›
                </span>
                {question}
              </summary>
              <p className="mt-3 text-sm leading-relaxed text-sourdine">{reponse}</p>
            </details>
          ))}
        </div>
        <div className="mt-12 rounded-2xl border border-accent/40 bg-accent-fonce/60 p-8 text-center">
          <h3 className="text-xl font-bold">{d.hero.titre1}</h3>
          <Link
            href={`/${locale}/app`}
            className="mt-4 inline-block rounded-full bg-accent px-7 py-3 font-bold text-fond hover:opacity-90"
          >
            {d.hero.ctaPrincipal}
          </Link>
        </div>
      </section>
    </main>
  );
}
