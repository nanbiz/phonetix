// The inline broad/narrow + stress display. The tooltip always shows the full form;
// this decides what the page text is reduced to, and a wrong regex silently changes
// every transcription a reader sees, so it is pinned here.
import { displayIpa, NARROW_DETAIL, STRESS_MARKS } from '../src/lib/display-ipa.ts';

let pass = 0, fail = 0;
function check(name: string, got: string, want: string) {
  const ok = got === want;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok ? '' : `  got=${JSON.stringify(got)} want=${JSON.stringify(want)}`}`);
  ok ? pass++ : fail++;
}

const BROAD = { narrow: false, hideStress: false };
const NARROW = { narrow: true, hideStress: false };

// Narrow → broad: aspiration and devoicing come off; the plain phonemes stay.
check('broad strips aspiration', displayIpa('kʰoːz̥', BROAD), 'koːz');
check('narrow keeps aspiration', displayIpa('kʰoːz̥', NARROW), 'kʰoːz̥');

// Detail that changes which sound is meant must survive the broad strip.
check('broad keeps length', displayIpa('ɑː', BROAD), 'ɑː');
check('broad keeps nasalization', displayIpa('ɑ̃', BROAD), 'ɑ̃');
check('broad keeps non-syllabic diphthong mark', displayIpa('aɪ̯', BROAD), 'aɪ̯');
check('broad keeps syllabic mark', displayIpa('n̩', BROAD), 'n̩');

// Stress marks: dropped only when asked; the broad/narrow choice does not touch them.
check('hideStress drops both marks', displayIpa('ˌɪntəˈnæʃənəl', { narrow: false, hideStress: true }), 'ɪntənæʃənəl');
check('stress kept by default', displayIpa('ˈkæt', BROAD), 'ˈkæt');
check('narrow + hideStress', displayIpa('kʰˈæt', { narrow: true, hideStress: true }), 'kʰæt');

// The two regexes must be global (used with .replace over the whole string).
check('NARROW_DETAIL is global', String(NARROW_DETAIL.flags.includes('g')), 'true');
check('STRESS_MARKS is global', String(STRESS_MARKS.flags.includes('g')), 'true');

console.log(`\n${pass}/${pass + fail} passed`);
if (fail) process.exit(1);
