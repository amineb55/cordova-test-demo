import type { Metadata } from "next";
import { notFound } from "next/navigation";
import Link from "next/link";
import "../globals.css";
import { directions, estLocale, locales, nomsLocales, type Locale } from "@/i18n/config";
import { dictionnaire } from "@/i18n/dictionnaires";

export function generateStaticParams() {
  return locales.map((locale) => ({ locale }));
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  if (!estLocale(locale)) return {};
  const d = dictionnaire(locale);
  return {
    title: d.meta.titre,
    description: d.meta.description,
    // Préversion : on retire ce noindex au branchement de earnaura.ai
    robots: { index: false, follow: false },
  };
}

export default async function LayoutLocale({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  if (!estLocale(locale)) notFound();
  const d = dictionnaire(locale);
  const autres = locales.filter((l) => l !== locale) as Locale[];

  return (
    <html lang={locale} dir={directions[locale]}>
      <body className="min-h-screen">
        <header className="sticky top-0 z-40 border-b border-bord/70 bg-fond/85 backdrop-blur">
          <div className="mx-auto flex max-w-5xl items-center gap-5 px-4 py-3">
            <Link href={`/${locale}`} className="text-lg font-bold tracking-tight">
              earn<span className="text-accent">aura</span>
            </Link>
            <nav className="ms-auto flex items-center gap-4 text-sm text-sourdine">
              <a href={`/${locale}#tarifs`} className="hover:text-encre">
                {d.entete.tarifs}
              </a>
              <a href={`/${locale}#faq`} className="hidden hover:text-encre sm:block">
                {d.entete.faq}
              </a>
              {autres.map((l) => (
                <Link
                  key={l}
                  href={`/${l}`}
                  className="rounded-full border border-bord px-2.5 py-1 text-xs font-semibold uppercase hover:border-faible"
                  aria-label={nomsLocales[l]}
                >
                  {l}
                </Link>
              ))}
              <Link
                href={`/${locale}/app`}
                className="rounded-full bg-accent px-3.5 py-1.5 text-sm font-bold text-fond hover:opacity-90"
              >
                {d.entete.cta}
              </Link>
            </nav>
          </div>
        </header>
        {children}
        <footer className="border-t border-bord/70 py-8">
          <div className="mx-auto flex max-w-5xl flex-col items-center gap-2 px-4 text-center text-sm text-faible">
            <a href="mailto:hello@earnaura.ai" className="text-sourdine hover:text-encre">
              {d.pied.contact}
            </a>
            <p>{d.pied.droits}</p>
            <p className="text-xs">{d.pied.preversion}</p>
          </div>
        </footer>
      </body>
    </html>
  );
}
