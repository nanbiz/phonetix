// What the settings say about the reader's languages, the same in the popup and on the phone.
import { named } from '@/data/languages';
import { SAYS } from '@/data/wording';
import { learned, type Settings } from '@/settings/shape';

/** The languages the reader reads, their own first, and those they learn, the one pages are
 *  replaced into first: "Deutsch · Englisch, lernt Spanisch · Italienisch". */
export function languagesSaid(settings: Settings): string {
  const learning = learned(settings);
  const reading = [settings.target, ...(settings.known ?? [])]
    .filter((lang, at, all) => lang && !learning.includes(lang) && all.indexOf(lang) === at)
    .map(named)
    .join(' · ');
  const learnt =
    learning.length > 0 ? SAYS['learning-it'].replace('%s', learning.map(named).join(' · ')) : '';
  return [reading, learnt].filter(Boolean).join(', ');
}
