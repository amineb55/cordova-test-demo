// i18n dès le premier composant (brief §2) : tout texte passe par des
// clés de traduction ; l'architecture est prête pour le RTL (l'arabe
// s'ajoute en V1.5 sans refonte : une entrée ici + un dictionnaire).
export const locales = ["fr", "en"] as const;
export type Locale = (typeof locales)[number];

export const localeParDefaut: Locale = "fr";

export const directions: Record<Locale, "ltr" | "rtl"> = {
  fr: "ltr",
  en: "ltr",
  // ar: "rtl"  ← V1.5
};

export const nomsLocales: Record<Locale, string> = {
  fr: "Français",
  en: "English",
};

export function estLocale(valeur: string): valeur is Locale {
  return (locales as readonly string[]).includes(valeur);
}
