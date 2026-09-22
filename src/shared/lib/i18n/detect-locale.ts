/**
 * The UI language for someone who never picked one: Russian on a Russian
 * device, English everywhere else. The locale store and `tRaw` share it, so a
 * service never answers in English next to a Russian UI.
 */
export function detectBrowserLocale(): "en" | "ru" {
  const lang = navigator.language?.slice(0, 2);
  return lang === "ru" ? "ru" : "en";
}
