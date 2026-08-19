import { NextRequest, NextResponse } from "next/server";
import { estLocale, localeParDefaut } from "@/i18n/config";

// Redirige / (et tout chemin sans préfixe de langue) vers /fr ou /en
// selon Accept-Language — le français reste la langue par défaut.
export function middleware(requete: NextRequest) {
  const { pathname } = requete.nextUrl;
  const premier = pathname.split("/")[1];
  if (estLocale(premier)) return NextResponse.next();

  const acceptees = requete.headers.get("accept-language") ?? "";
  const locale = /^en\b|,en\b/i.test(acceptees) && !/^fr\b/i.test(acceptees)
    ? "en"
    : localeParDefaut;
  const url = requete.nextUrl.clone();
  url.pathname = `/${locale}${pathname === "/" ? "" : pathname}`;
  return NextResponse.redirect(url);
}

export const config = {
  matcher: ["/((?!_next|api|favicon.ico|.*\\..*).*)"],
};
