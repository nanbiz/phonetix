import { sendMessage, onMessage } from '@/lib/messaging';
import {
  BLOCKED_TAGS,
  PHONETIX_CLASS,
  ORIG_CLASS,
  IPA_CLASS,
  MODE_CLASSES,
  CHROME_SELECTOR,
  WORD_RE,
  MAX_WORD_LENGTH,
  TECHNICAL_RE,
  PHONETIX_CSS,
  TOOLTIP_CSS,
} from '@/lib/constants';
import { DefaultAccents, Languages, LanguageNames, AccentsByLanguage, WiktionaryAnchors, BLOCK_TAGS } from '@/lib/types';
import type { LanguageOption, Mode, ResolvedIpa, PhonemeResult } from '@/lib/types';

type Language = string;
import { IPA_SYMBOLS, TERM_LINKS, describeSymbol, tokenizeIPA, wikimediaAudioURL } from '@/lib/ipa-symbols';
import type { IPASymbolInfo } from '@/lib/ipa-symbols';
import { segment, words as wordsOf } from '@/lib/segment';

// =====================================================================
//  Module state
// =====================================================================

let isEnabled = true;
let languageOption: LanguageOption = 'auto';
let pageLang: Language = 'en';
/** Chosen voice per language. A page can hold several languages at once, so an
 *  accent only means anything relative to one of them. Unset languages use their
 *  default voice. */
let accents: Record<string, string> = {};

function voiceFor(lang: Language): string {
  return accents[lang] || DefaultAccents[lang] || lang;
}
let mode: Mode = 'showOriginalOnHover';
/** Stress marks read like stray apostrophes mid-sentence, so they can be left
 *  out of the page. The tooltip always shows the full transcription. */
let hideStress = false;

const STRESS_MARKS = /[\u02C8\u02CC]/g;

function displayIpa(ipa: string): string {
  return hideStress ? ipa.replace(STRESS_MARKS, '') : ipa;
}

// =====================================================================
//  Pure helpers (no side effects, no DOM)
// =====================================================================

/** Strip espeak language-switch markers like (en), (de) from IPA. */
function cleanIPA(raw: string): string {
  return raw.replace(/\([a-z]{2}\)/g, '');
}

/** Extract language from espeak markers. First marker wins. */
function extractIPALang(raw: string): Language | null {
  const m = raw.match(/\(([a-z]{2})\)/);
  if (!m) return null;
  return m[1] in Languages ? (m[1] as Language) : null;
}

function wiktionaryURL(lang: Language, title: string): string {
  return `https://${lang}.wiktionary.org/wiki/${encodeURIComponent(title)}#${WiktionaryAnchors[lang] || 'Pronunciation'}`;
}

/** Symbol type → short CSS class. */
const TYPE_CLASS: Record<string, string> = {
  consonant: 'C', vowel: 'V', suprasegmental: 'S', diacritic: 'D',
};

// =====================================================================
//  SVG icons (inline, no external deps)
// =====================================================================

const ICO_SPEAKER = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/></svg>';
const ICO_SPEAKER_SM = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>';
// A face speaking, with a sound wave leaving the mouth: a person saying the word.
// The plain microphone read as a mute button, and head-and-shoulders was unclear.
// A person with sound waves (Iconify mdi:account-voice): a human saying the word.
const ICO_MIC = '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M9 5a4 4 0 0 1 4 4a4 4 0 0 1-4 4a4 4 0 0 1-4-4a4 4 0 0 1 4-4m0 10c2.67 0 8 1.34 8 4v2H1v-2c0-2.66 5.33-4 8-4m7.76-9.64c2.02 2.2 2.02 5.25 0 7.27l-1.68-1.69c.84-1.18.84-2.71 0-3.89zM20.07 2c3.93 4.05 3.9 10.11 0 14l-1.63-1.63c2.77-3.18 2.77-7.72 0-10.74z"/></svg>';
/** Synthesized speech (espeak): a robot head. */
const ICO_ROBOT = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="8" width="16" height="12" rx="2"/><path d="M12 8V4"/><circle cx="12" cy="3" r="1"/><path d="M9 13h.01"/><path d="M15 13h.01"/><path d="M9 17h6"/><path d="M1 12v3"/><path d="M23 12v3"/></svg>';
const ICO_WIKT = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path fill="currentColor" d="M2.22 18.6v.01c-.35-.21-.61-.5-.71-.84l-.07-.37L.21 3.36c-.03-.45.17-.9.57-1.25c.39-.36.97-.6 1.62-.66L15.35.22a2.8 2.8 0 0 1 1.7.35a1.5 1.5 0 0 1 .77 1.13l1.23 14.12c.03.45-.17.9-.57 1.25a2.85 2.85 0 0 1-1.62.67L3.92 18.95a2.75 2.75 0 0 1-1.7-.35m-1-1.1c.02.18.07.35.15.5l.02.25c.05.56.4 1.03.9 1.34c.51.3 1.19.46 1.9.4l13.34-1.27a3.15 3.15 0 0 0 1.8-.74c.45-.4.71-.93.66-1.49L18.73 1.87a1.77 1.77 0 0 0-.9-1.33a2.9 2.9 0 0 0-1.24-.4a3.2 3.2 0 0 0-1.27-.12L2.4 1.23a3.1 3.1 0 0 0-1.74.72c-.44.39-.7.9-.64 1.44l1.22 14.1zm1.2 1.9a1.6 1.6 0 0 1-.78-1a2 2 0 0 0 .47.39c.49.3 1.14.44 1.84.38l12.93-1.22c.7-.06 1.31-.33 1.74-.72c.44-.38.7-.9.64-1.43L18.04 1.69a1.62 1.62 0 0 0-.62-1.11c.1.04.2.09.29.15c.46.28.76.7.8 1.16l1.26 14.62c.04.48-.18.94-.59 1.3c-.4.37-1 .63-1.67.7L4.17 19.75a2.9 2.9 0 0 1-1.76-.36ZM1.21 5.3l4.34-.5l.06.47l-.28.04c-.28.03-.48.12-.6.26a.57.57 0 0 0-.15.46a12 12 0 0 0 .53 1.33l2.91 6.12l1.15-5.56l-.8-1.68c-.16-.27-.31-.5-.48-.7a1 1 0 0 0-.28-.23a1.4 1.4 0 0 0-.42-.17c-.1-.02-.25-.02-.48 0l-.08.02l-.06-.48l4.56-.53l.06.48l-.38.04c-.3.04-.5.13-.6.26a.67.67 0 0 0-.13.53c0 .02 0 .06.03.15l.15.4l3.2 6.84l1.32-6.48c.16-.75.22-1.25.18-1.51a.57.57 0 0 0-.14-.32a.57.57 0 0 0-.3-.18c-.2-.05-.47-.06-.8-.02h-.08l-.06-.48l3.53-.4l.06.48h-.08c-.29.04-.5.12-.66.24c-.15.12-.3.33-.42.64c-.08.2-.2.7-.35 1.5l-2.01 9.9l-.45.05l-3.4-7.06l-1.61 7.64l-.42.04l-4.56-9.42q-.51-1.05-.63-1.23a1 1 0 0 0-.47-.4a1.6 1.6 0 0 0-.76-.07h-.08Z"/></svg>';

// =====================================================================
//  Audio playback
// =====================================================================

// Audio is decoded from bytes and played through the Web Audio API, never loaded
// into the page as a <audio>/<Audio> element. A page's media-src CSP blocks the
// element from loading a Wikimedia URL or even a blob, silently, so the buttons
// made no sound on exactly the strict-CSP sites people read. Decoding bytes we
// already hold is not a resource load, so no CSP applies. The bytes are fetched
// by the background, which is not subject to the page's CSP either.

let audioCtx: AudioContext | null = null;
let currentSource: AudioBufferSourceNode | null = null;

function stopAudio(): void {
  if (currentSource) { try { currentSource.stop(); } catch { /* already stopped */ } currentSource = null; }
}

async function playBytes(bytes: number[]): Promise<void> {
  if (!bytes.length) return;
  stopAudio();
  if (!audioCtx) audioCtx = new AudioContext();
  if (audioCtx.state === 'suspended') await audioCtx.resume();

  // decodeAudioData needs its own copy of the buffer; a plain number[] arrives
  // over messaging, so it is packed back into an ArrayBuffer here.
  const buffer = new Uint8Array(bytes).buffer;
  const decoded = await audioCtx.decodeAudioData(buffer);

  const source = audioCtx.createBufferSource();
  source.buffer = decoded;
  source.connect(audioCtx.destination);
  source.start();
  currentSource = source;
}

/** Play a Commons/Wiktionary recording, fetched as bytes by the background. */
function playAudioUrl(url: string): void {
  sendMessage('fetchAudio', { url })
    .then(playBytes)
    .catch((e) => console.warn('[Phonetix] audio playback failed:', e));
}

/** Play the recording of a single IPA symbol from Wikimedia Commons. */
function playSymbol(filename: string): void {
  playAudioUrl(wikimediaAudioURL(filename));
}

/**
 * Speak a word via espeak-ng synthesis. The engine runs in the offscreen document
 * on Chrome and the background on Firefox; either way it returns WAV bytes, which
 * play through the same CSP-proof path as every other sound.
 */
function speakWord(word: string, lang: Language): void {
  const voice = voiceFor(lang);
  sendMessage('synthesizeAudio', { word, voice })
    .then(playBytes)
    .catch((e) => console.warn('[Phonetix] synthesizeAudio failed:', e));
}

// =====================================================================
//  Tooltip – DOM references
// =====================================================================

let ttHost: HTMLDivElement | null = null;
let ttEl: HTMLDivElement | null = null;
let ttDetail: HTMLElement | null = null;
let activeSym: HTMLElement | null = null;
let hoverTimer: ReturnType<typeof setTimeout> | null = null;
let hideTimer: ReturnType<typeof setTimeout> | null = null;
let curTarget: HTMLElement | null = null;
let highlightedEl: HTMLElement | null = null;
let curTargetRect: DOMRect | null = null;
let ttAbove = false;
let ttVisible = false;
/** Pinned = the user pressed inside the tooltip (to select or click). Hover
 *  timers cannot dismiss it while pinned. */
let ttPinned = false;

/** True when the event happened inside the tooltip, shadow root included. */
function inTooltip(e: Event): boolean {
  if (!ttHost) return false;
  const path = (e as MouseEvent).composedPath?.() ?? [];
  return e.target === ttHost || path.includes(ttHost);
}

// =====================================================================
//  Tooltip – lifecycle
// =====================================================================

function initTooltip(): void {
  if (ttHost) return;
  ttHost = document.createElement('div');
  ttHost.id = 'phonetix-tooltip-host';
  ttHost.style.cssText = 'position:fixed;top:0;left:0;width:0;height:0;z-index:2147483647;pointer-events:none;overflow:visible;';
  document.body.appendChild(ttHost);

  // Open, so a test can measure the card and read what it says. The boundary
  // still keeps the page's CSS out either way; closed only hides it from the
  // tests that have to prove it does not resize under the cursor.
  const shadow = ttHost.attachShadow({ mode: 'open' });
  const style = document.createElement('style');
  style.textContent = TOOLTIP_CSS;
  shadow.appendChild(style);

  ttEl = document.createElement('div');
  ttEl.className = 'px-tt';
  shadow.appendChild(ttEl);
}

/**
 * Is (x, y) inside the tooltip's safe zone?
 * The zone covers the tooltip + padding on sides/top, and extends
 * through the arrow gap down to the word's top edge (above case)
 * or up to the word's bottom edge (below case). It does NOT extend
 * past the word line, so neighboring words can trigger an immediate switch.
 */
function isInSafeZone(x: number, y: number): boolean {
  if (!ttEl || !curTargetRect) return false;
  const r = ttEl.getBoundingClientRect();
  if (r.width === 0) return false;

  const mx = 30, my = 15;
  const left = r.left - mx;
  const right = r.right + mx;
  let top: number, bottom: number;

  if (ttAbove) {
    top = r.top - my;
    bottom = curTargetRect.top; // stop at word's top edge
  } else {
    top = curTargetRect.bottom; // stop at word's bottom edge
    bottom = r.bottom + my;
  }

  return x >= left && x <= right && y >= top && y <= bottom;
}

function setupTooltipEvents(): void {
  // Hover on phonetix spans
  document.addEventListener('mouseover', (e) => {
    const t = (e.target as HTMLElement).closest(`.${PHONETIX_CLASS}`) as HTMLElement | null;
    if (!t || t === curTarget) return;
    // A pinned tooltip is never replaced by hovering another word.
    if (ttPinned) return;
    // Don't switch words if mouse is in the tooltip's safe zone
    if (ttVisible && isInSafeZone(e.clientX, e.clientY)) return;
    clearTimers();
    curTarget = t;
    if (ttVisible) { showTooltip(t); } else { hoverTimer = setTimeout(() => showTooltip(t), 700); }
  });

  document.addEventListener('mouseout', (e) => {
    const t = (e.target as HTMLElement).closest(`.${PHONETIX_CLASS}`) as HTMLElement | null;
    if (t !== curTarget) return;
    // Ignore child-to-child transitions within the same span (e.g. CSS visibility swap in hover modes)
    const related = (e as MouseEvent).relatedTarget as HTMLElement | null;
    if (related?.closest(`.${PHONETIX_CLASS}`) === t) return;
    clearTimers();
    hideTimer = setTimeout(() => { hideTooltip(); curTarget = null; }, 350);
  });

  // Pressing the mouse inside the tooltip pins it: a drag to select the IPA
  // leaves the tooltip's box and would otherwise trip mouseout/mouseleave and
  // dismiss it mid-selection, before the copy. A pinned tooltip is dismissed
  // only by pressing outside it, or by Escape.
  document.addEventListener('mousedown', (e) => {
    if (!ttVisible) return;
    if (inTooltip(e as MouseEvent)) { clearTimers(); ttPinned = true; return; }
    clearTimers(); hideTooltip(true); curTarget = null;
  }, true);

  document.addEventListener('keydown', (e) => {
    if ((e as KeyboardEvent).key === 'Escape' && ttVisible) { clearTimers(); hideTooltip(true); curTarget = null; }
  });

  window.addEventListener('scroll', () => { if (ttVisible) { clearTimers(); hideTooltip(); curTarget = null; } }, { passive: true });

  // Keep tooltip alive when hovered
  if (ttEl) {
    ttEl.addEventListener('mouseenter', () => { if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; } });
    ttEl.addEventListener('mouseleave', () => { hideTimer = setTimeout(() => { hideTooltip(); curTarget = null; }, 200); });
  }

  // Buffer zone: cancel hide timer when mouse is in the safe zone
  document.addEventListener('mousemove', (e) => {
    if (!hideTimer) return;
    if (isInSafeZone(e.clientX, e.clientY)) {
      clearTimeout(hideTimer); hideTimer = null;
    }
  });
}

function clearTimers(): void {
  if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; }
  if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
}

// =====================================================================
//  Tooltip – show / hide / position
// =====================================================================

function showTooltip(target: HTMLElement): void {
  if (!ttEl || !ttHost) return;

  // Read final data from span (already clean — set by createPhoneticSpan)
  const word = target.dataset.original || '';
  const lang = (target.dataset.lang as Language) || pageLang;
  const ipa = target.dataset.ipa || '';
  const src = target.dataset.src || 'dict';
  if (!word || !ipa) return;

  // Update highlight
  if (highlightedEl && highlightedEl !== target) highlightedEl.classList.remove('px-active');
  target.classList.add('px-active');
  highlightedEl = target;

  renderTooltip(word, ipa, lang, src);

  ttEl.style.display = 'block';
  ttEl.classList.remove('visible', 'above', 'below');
  ttHost.style.pointerEvents = 'auto';

  // Position
  const rect = target.getBoundingClientRect();
  curTargetRect = rect;
  const th = ttEl.offsetHeight;
  const tw = ttEl.offsetWidth;
  // Anchor to the word's edge rather than a computed top: the Wiktionary row is
  // filled in asynchronously, and a tooltip pinned by its top would grow downwards
  // over the very word it describes.
  if (rect.top > th + 12) {
    ttAbove = true;
    ttEl.classList.add('above');
    ttEl.style.top = 'auto';
    ttEl.style.bottom = `${window.innerHeight - rect.top + 8}px`;
  } else {
    ttAbove = false;
    ttEl.classList.add('below');
    ttEl.style.bottom = 'auto';
    ttEl.style.top = `${rect.bottom + 8}px`;
  }
  const cx = rect.left + rect.width / 2;
  let left = Math.max(8, Math.min(cx - tw / 2, window.innerWidth - tw - 8));
  ttEl.style.setProperty('--arrow-left', `${Math.max(12, Math.min(cx - left, tw - 12))}px`);
  ttEl.style.left = `${left}px`;

  ttVisible = true;
  requestAnimationFrame(() => ttEl?.classList.add('visible'));
}

function hideTooltip(force = false): void {
  if (ttPinned && !force) return;
  ttPinned = false;
  if (!ttEl || !ttHost) return;
  if (highlightedEl) { highlightedEl.classList.remove('px-active'); highlightedEl = null; }
  curTargetRect = null;
  ttVisible = false;
  ttEl.classList.remove('visible');
  ttHost.style.pointerEvents = 'none';
  stopAudio();
  setTimeout(() => { if (ttEl && !ttEl.classList.contains('visible')) ttEl.style.display = 'none'; }, 150);
}

// =====================================================================
//  Tooltip – render (pure display, no data transforms)
// =====================================================================

function renderTooltip(word: string, ipa: string, lang: Language, src: string): void {
  if (!ttEl) return;
  ttEl.innerHTML = '';

  // ── Row 1: word · lang · source ··· [W] [recording] ──
  const r1 = el('div', 'px-r1');
  r1.appendChild(txt('span', 'px-word', word));

  // Language and accent are one fact — what the word is read as — so they share one
  // neutral tag. The source (dict/espeak) is a different fact and keeps its own
  // colour, set apart from the pair.
  const accentId = accents[lang] || DefaultAccents[lang] || lang;
  const hasAccentChoice = Object.keys(AccentsByLanguage[lang] || {}).length > 1;
  const accentLabel = hasAccentChoice ? AccentsByLanguage[lang]?.[accentId] : '';
  const langTag = txt('span', 'px-lang', accentLabel ? `${lang.toUpperCase()} · ${accentLabel}` : lang.toUpperCase());
  langTag.title = accentLabel
    ? `Read as ${LanguageNames[lang] || lang}, ${accentLabel} accent`
    : `Read as ${LanguageNames[lang] || lang}`;
  r1.appendChild(langTag);

  const srcTag = txt('span', `px-src px-src-${src}`, src === 'espeak' ? 'espeak' : 'dict');
  srcTag.title = src === 'espeak'
    ? 'Synthesized by espeak: no dictionary has this word'
    : 'From the Wiktionary dictionary: a human wrote this pronunciation';
  r1.appendChild(srcTag);
  r1.appendChild(el('div', 'px-spacer'));

  const wiktBtn = el('a', 'px-btn disabled') as HTMLAnchorElement;
  wiktBtn.innerHTML = ICO_WIKT;
  wiktBtn.title = 'Wiktionary';
  wiktBtn.target = '_blank';
  wiktBtn.rel = 'noopener noreferrer';
  wiktBtn.href = wiktionaryURL(lang, word);
  r1.appendChild(wiktBtn);

  // Wiktionary recording: a real human saying the word. Enabled only if one exists.
  const audioBtn = btn('px-btn disabled', ICO_MIC, 'Human recording (Wiktionary) — checking…');
  r1.appendChild(audioBtn);
  ttEl.appendChild(r1);

  // ── The pronunciation itself, and it is the interactive part ──
  // One IPA, shown large. Each symbol explains itself on hover and speaks when
  // clicked, so there is no second copy of the same transcription to read.
  const r2 = el('div', 'px-r2');
  r2.appendChild(txt('span', 'px-slash', '/'));
  const line = el('span', 'px-ipa-line');
  r2.appendChild(line);
  r2.appendChild(txt('span', 'px-slash', '/'));

  const ttsBtn = btn('px-btn px-btn-tts', ICO_ROBOT, `Robot voice (espeak, ${voiceFor(lang)})`);
  ttsBtn.addEventListener('click', (e) => { e.stopPropagation(); speakWord(word, lang); });
  r2.appendChild(ttsBtn);
  ttEl.appendChild(r2);

  const detail = el('div', 'px-detail');
  ttEl.appendChild(detail);
  ttDetail = detail;
  renderSymbols(line, displayIpa(ipa));

  // Wiktionary is looked up only to enrich the row-1 controls: it enables the
  // link and the recording. The IPA and its source tag stay exactly what the
  // page shows — one pronunciation, one source, no second opinion.
  sendMessage('checkWiktionary', { lang, word }).then((info) => {
    const linkLang = info.foundLang || lang;

    if (info.exists && info.matchedTitle) {
      wiktBtn.classList.remove('disabled');
      wiktBtn.href = wiktionaryURL(linkLang, info.matchedTitle);
    }

    if (info.audioUrl) {
      audioBtn.classList.remove('disabled');
      audioBtn.title = 'Human recording (Wiktionary)';
      audioBtn.addEventListener('click', (e) => { e.stopPropagation(); playAudioUrl(info.audioUrl!); });
    } else {
      audioBtn.title = 'No human recording on Wiktionary';
    }
  }).catch(() => { audioBtn.title = 'No human recording on Wiktionary'; });
}

/**
 * Lay the IPA out as its own symbols: each one names itself on hover and speaks
 * when clicked. The transcription a reader looks at and the thing they explore
 * are one and the same, rather than a small line above a grid repeating it.
 */
function renderSymbols(container: HTMLElement, ipa: string): void {
  container.innerHTML = '';
  activeSym = null;

  let first: HTMLElement | null = null;

  for (const tok of tokenizeIPA(ipa)) {
    if (!tok.trim()) continue;
    const info = describeSymbol(tok);
    const cls = info ? TYPE_CLASS[info.type] || '' : '';

    // The symbol itself is the link to the article on this sound, so the reader
    // clicks the thing they are looking at. The recording is on the speaker beside
    // the description, which is where the rest of the sound's detail is.
    const sym = info?.wiki
      ? (el('a', `px-sym ${cls} clickable`) as HTMLAnchorElement)
      : el('span', `px-sym ${cls}`);
    sym.textContent = tok;

    if (info?.wiki) {
      const link = sym as HTMLAnchorElement;
      link.href = `https://en.wikipedia.org/wiki/${info.wiki}`;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.title = `${info.name}: read about this sound`;
      link.addEventListener('click', (e) => e.stopPropagation());
    }

    if (info) {
      // The detail stays on the symbol it was asked about: leaving the symbol
      // must not blank it, or the description is gone the moment you look at it.
      sym.addEventListener('mouseenter', () => showDetail(sym, tok, info));
      if (!first) first = sym;
    }

    container.appendChild(sym);
  }

  // Something to read the moment the tooltip opens, rather than an instruction.
  if (first) first.dispatchEvent(new MouseEvent('mouseenter'));
}

/** Build a symbol's description with each phonetic term linked to its article. */
function describedName(name: string): HTMLElement {
  const line = el('span', 'px-detail-name');

  // Split on the terms themselves, keeping the punctuation and spacing between them.
  for (const part of name.split(/([\p{L}-]+)/u)) {
    if (!part) continue;
    const title = TERM_LINKS[part.toLowerCase()];
    if (!title) {
      line.appendChild(document.createTextNode(part));
      continue;
    }

    const link = el('a', 'px-detail-link') as HTMLAnchorElement;
    link.textContent = part;
    link.href = `https://en.wikipedia.org/wiki/${title}`;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.title = `${part}: read about it on Wikipedia`;
    link.addEventListener('click', (e) => e.stopPropagation());
    line.appendChild(link);
  }

  return line;
}

/** Describe one symbol, and mark it as the one being described. */
function showDetail(sym: HTMLElement, tok: string, info: IPASymbolInfo): void {
  if (!ttDetail) return;
  activeSym?.classList.remove('active');
  sym.classList.add('active');
  activeSym = sym;

  ttDetail.innerHTML = '';

  const text = el('span', 'px-detail-text');

  // A description is a stack of independent facts — "r-colored open-mid central
  // vowel" is r-colouring, and a height, and a backness, and a vowel — so each term
  // links to the article on that term. Linking the phrase as a whole would send the
  // reader to one of the four and hide the rest.
  text.appendChild(describedName(info.name));

  if (info.example) text.appendChild(txt('span', 'px-detail-eg', info.example));
  ttDetail.appendChild(text);

  if (info.audio) {
    const file = info.audio;
    const spk = btn('px-btn px-detail-spk', ICO_SPEAKER, `Hear ${info.name}`);
    spk.addEventListener('click', (e) => { e.stopPropagation(); playSymbol(file); });
    ttDetail.appendChild(spk);
  }

  // Real mouths, moving: Seeing Speech (University of Glasgow) films every sound
  // under MRI, ultrasound and animation. The films are not free to redistribute but
  // the site opens one directly from this hash, so the link goes straight to it.
  if (info.seeing) {
    const mri = el('a', 'px-detail-mri') as HTMLAnchorElement;
    mri.textContent = 'MRI';
    mri.href = `https://www.seeingspeech.ac.uk/ipa-charts/${info.seeing}`;
    mri.target = '_blank';
    mri.rel = 'noopener noreferrer';
    mri.title = `Watch a mouth say ${info.name} on MRI and ultrasound (Seeing Speech)`;
    mri.addEventListener('click', (e) => e.stopPropagation());
    ttDetail.appendChild(mri);
  }

  // The mouth making the sound: a section through the head with the tongue and lips
  // where they have to be. It is a picture of the answer to "how do I say this".
  if (info.diagram) {
    const shown = tok;
    const holder = el('a', 'px-detail-diagram') as HTMLAnchorElement;
    holder.href = `https://commons.wikimedia.org/wiki/File:${encodeURIComponent(info.diagram)}`;
    holder.target = '_blank';
    holder.rel = 'noopener noreferrer';
    holder.title = `How ${info.name} is articulated`;
    holder.addEventListener('click', (e) => e.stopPropagation());
    ttDetail.appendChild(holder);

    sendMessage('symbolDiagram', { file: info.diagram })
      .then((url) => {
        // The reader may have moved on to another symbol while this was fetched.
        if (!url || activeSym !== sym || sym.textContent !== shown) return;
        const img = document.createElement('img');
        img.src = url;
        img.alt = `Articulation of ${info.name}`;
        holder.appendChild(img);
      })
      .catch(() => {});
  }
}

// DOM helpers
function el(tag: string, cls: string): HTMLElement {
  const e = document.createElement(tag);
  e.className = cls;
  return e;
}
function txt(tag: string, cls: string, text: string): HTMLElement {
  const e = el(tag, cls);
  e.textContent = text;
  return e;
}
function btn(cls: string, svg: string, title: string): HTMLButtonElement {
  const b = document.createElement('button');
  b.className = cls;
  b.innerHTML = svg;
  b.title = title;
  return b;
}

// =====================================================================
//  DOM Processing — span creation (data is finalized HERE, not later)
// =====================================================================

/**
 * Create a phonetix span with all data finalized:
 * - IPA is cleaned (no espeak markers)
 * - Language is resolved (espeak markers override block detection)
 * The tooltip just reads these values — no re-processing.
 */
function createPhoneticSpan(original: string, r: ResolvedIpa, trailing = ''): HTMLSpanElement {
  const finalLang = extractIPALang(r.ipa) || r.lang;
  const finalIpa = cleanIPA(r.ipa);

  const span = document.createElement('span');
  span.className = PHONETIX_CLASS;
  span.dataset.original = original;
  span.dataset.ipa = finalIpa;     // the pronunciation alone, without the trailing mark
  span.dataset.lang = finalLang;   // resolution language (may differ from block for loanwords)
  span.dataset.src = r.src;        // 'dict' | 'espeak' — drives the tooltip source label

  const origSpan = document.createElement('span');
  origSpan.className = ORIG_CLASS;
  origSpan.textContent = original + trailing;

  const ipaSpan = document.createElement('span');
  ipaSpan.className = IPA_CLASS;
  ipaSpan.textContent = displayIpa(finalIpa) + trailing;

  span.appendChild(origSpan);
  span.appendChild(ipaSpan);
  return span;
}

/** Punctuation that closes a word rather than joining or opening one. The hover
 *  modes stack the word and its IPA in one cell as wide as the wider of the two,
 *  so a mark left outside the cell drifts away from the word it belongs to when
 *  the word is the wider one. Carried inside both layers, it stays attached. */
const TRAILING_MARK = /^[.,;:!?…)\]}»”’]+/u;

// =====================================================================
//  Page processing
// =====================================================================

interface TextBlock { blockElement: Element; textNodes: Text[]; text: string; }

/**
 * Re-read the page under a changed setting.
 *
 * The page is stripped of its spans before it can be rewritten, so a failure
 * here would otherwise leave it stripped for good: no spans, no observer, and no
 * sign of why. The failure is recorded on the document (a test can read it, as it
 * cannot see a content script's console) and the page is put back the way it was.
 */
async function reprocess(): Promise<void> {
  if (!isEnabled) return;
  stopObserver();
  revertAll();
  try {
    await processPage();
    delete document.documentElement.dataset.pxerror;
  } catch (e) {
    document.documentElement.dataset.pxerror = String(e);
    console.error('[Phonetix] reprocessing the page failed:', e);
  } finally {
    observeDOM();
  }
}

async function processPage(root: Element = document.body): Promise<void> {
  if (languageOption === 'auto') {
    await processMultilingual(root);
  } else {
    const nodes = collectTextNodes(root);
    const words = uniqueWords(nodes, pageLang);
    if (words.length === 0) return;

    const voice = voiceFor(pageLang);
    const ipaMap = await sendMessage('phonemize', { words, voice, lang: pageLang });
    await applyTransforms(nodes, ipaMap, pageLang, voice);
  }
}

async function processMultilingual(root: Element = document.body): Promise<void> {
  const blocks = groupByBlock(root);
  if (blocks.length === 0) return;

  const blockLangs = await detectBlockLanguages(blocks);

  // The most common language is the page language, tracked for the popup — but
  // only from a full-page pass, not from an incremental subtree the observer adds.
  if (root === document.body) {
    const counts = new Map<Language, number>();
    for (const l of blockLangs) counts.set(l, (counts.get(l) || 0) + 1);
    let best = pageLang, bestN = 0;
    for (const [l, n] of counts) { if (n > bestN) { best = l; bestN = n; } }
    pageLang = best;
    await storage.setItem('local:detectedLanguage', pageLang);
  }

  // Group by voice → phonemize + Wiktionary batch in parallel
  const byVoice = new Map<string, { nodes: Text[]; words: Set<string>; lang: Language }>();
  for (let i = 0; i < blocks.length; i++) {
    const lang = blockLangs[i];
    const voice = voiceFor(lang);
    if (!byVoice.has(voice)) byVoice.set(voice, { nodes: [], words: new Set(), lang });
    const g = byVoice.get(voice)!;
    for (const tn of blocks[i].textNodes) {
      g.nodes.push(tn);
      for (const w of wordsOf(tn.nodeValue || '', lang, MAX_WORD_LENGTH)) g.words.add(w);
    }
  }

  // The page's own languages are the cross-dictionary fallback for loanwords and
  // proper nouns (a lone English name in a German sentence), derived from what was
  // detected, not a fixed list.
  const pageLangs = [...new Set([...byVoice.values()].map((g) => g.lang))];

  await Promise.all([...byVoice.entries()].map(async ([voice, g]) => {
    const words = [...g.words];
    if (words.length === 0) return;

    const ipaMap = await sendMessage('phonemize', { words, voice, lang: g.lang, fallbacks: pageLangs });
    await applyTransforms(g.nodes, ipaMap, g.lang, voice);
  }));
}

// =====================================================================
//  DOM helpers for processing
// =====================================================================

function groupByBlock(root: Node): TextBlock[] {
  const nodes = collectTextNodes(root);
  const map = new Map<Element, Text[]>();
  for (const n of nodes) {
    const block = findBlock(n);
    if (!map.has(block)) map.set(block, []);
    map.get(block)!.push(n);
  }
  return [...map.entries()].map(([blockElement, textNodes]) => ({
    blockElement,
    textNodes,
    text: textNodes.map(n => n.nodeValue?.trim() || '').join(' '),
  }));
}

function findBlock(node: Node): Element {
  let el = node.parentElement;
  while (el && el !== document.body) {
    if (BLOCK_TAGS.has(el.tagName)) return el;
    el = el.parentElement;
  }
  return document.body;
}

function findExplicitLang(el: Element): Language | null {
  let cur: Element | null = el;
  while (cur && cur !== document.documentElement) {
    const l = cur.getAttribute('lang')?.split('-')[0]?.toLowerCase();
    if (l && l in Languages) return l as Language;
    cur = cur.parentElement;
  }
  return null;
}

async function detectBlockLanguages(blocks: TextBlock[]): Promise<Language[]> {
  const results: Language[] = new Array(blocks.length).fill(pageLang);
  // An explicit lang attribute is authoritative; those blocks skip detection.
  const explicit: (Language | null)[] = blocks.map((b) => findExplicitLang(b.blockElement));

  const payload = blocks.map((b) => ({
    text: b.text,
    words: wordsOf(b.text, pageLang, MAX_WORD_LENGTH).slice(0, 30),
  }));

  try {
    const detected = await sendMessage('detectBlocks', { pageLang, blocks: payload });
    for (let i = 0; i < blocks.length; i++) {
      results[i] = explicit[i] || (detected[i] as Language) || pageLang;
    }
  } catch (e) {
    console.warn('[Phonetix] Block language detection failed:', e);
    for (let i = 0; i < blocks.length; i++) results[i] = explicit[i] || pageLang;
  }
  return results;
}

function collectTextNodes(root: Node): Text[] {
  const nodes: Text[] = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_SKIP;
      if (BLOCKED_TAGS.has(p.tagName)) return NodeFilter.FILTER_SKIP;
      if (p.closest(`.${PHONETIX_CLASS}`)) return NodeFilter.FILTER_SKIP;
      // Never touch text the user is editing. Rich editors (claude.ai, Google Docs,
      // most comment boxes) are contenteditable elements, not <textarea>, and
      // rewriting them would corrupt what is being typed.
      if (p.isContentEditable) return NodeFilter.FILTER_SKIP;
      // Skip UI chrome (nav, footer, buttons, ARIA landmarks) — not reading content.
      if (p.closest(CHROME_SELECTOR)) return NodeFilter.FILTER_SKIP;
      const v = node.nodeValue?.trim();
      if (!v) return NodeFilter.FILTER_SKIP;
      // Skip text nodes that contain URLs, IP addresses, or ISO timestamps
      if (TECHNICAL_RE.test(v)) return NodeFilter.FILTER_SKIP;
      // Skip bare domains/filenames (no spaces, contains word.word pattern)
      if (!/\s/.test(v) && /\w\.\w/.test(v)) return NodeFilter.FILTER_SKIP;
      // Skip URL-like link text inside <a> tags (e.g. "geko.com")
      const a = p.closest('a');
      if (a && /^\S+\.\S+$/.test(a.textContent?.trim() || '')) return NodeFilter.FILTER_SKIP;
      return NodeFilter.FILTER_ACCEPT;
    },
  });
  let n: Node | null;
  while ((n = walker.nextNode())) nodes.push(n as Text);
  return nodes;
}

function uniqueWords(nodes: Text[], lang: Language): string[] {
  const s = new Set<string>();
  for (const n of nodes)
    for (const w of wordsOf(n.nodeValue || '', lang, MAX_WORD_LENGTH)) s.add(w);
  return [...s];
}

// Homograph word sets per language (words with >1 pronunciation), lazily fetched.
const homographSets = new Map<Language, Set<string>>();

async function getHomographSet(lang: Language): Promise<Set<string>> {
  const cached = homographSets.get(lang);
  if (cached) return cached;
  let set = new Set<string>();
  try {
    set = new Set(await sendMessage('getHomographWords', { lang }));
  } catch (e) {
    console.warn('[Phonetix] getHomographWords failed:', e);
  }
  homographSets.set(lang, set);
  return set;
}

/**
 * For every homograph occurrence, resolve its context-appropriate IPA using the
 * word's node-local neighbours. Returns per-node overrides keyed by word index.
 */
async function computeHomographOverrides(
  nodes: Text[], lang: Language, voice: string
): Promise<Map<Text, Map<number, ResolvedIpa>>> {
  const out = new Map<Text, Map<number, ResolvedIpa>>();
  const set = await getHomographSet(lang);
  if (set.size === 0) return out;

  const items: { word: string; tokens: string[]; index: number }[] = [];
  const locs: { node: Text; wordIdx: number }[] = [];

  for (const node of nodes) {
    const tokens = wordsOf(node.nodeValue || '', lang);
    for (let i = 0; i < tokens.length; i++) {
      if (set.has(tokens[i])) {
        items.push({ word: tokens[i], tokens, index: i });
        locs.push({ node, wordIdx: i });
      }
    }
  }
  if (items.length === 0) return out;

  try {
    const results = await sendMessage('disambiguate', { lang, voice, items });
    for (let k = 0; k < results.length; k++) {
      const ipa = results[k];
      if (!ipa) continue;
      const { node, wordIdx } = locs[k];
      if (!out.has(node)) out.set(node, new Map());
      out.get(node)!.set(wordIdx, { ipa, lang, src: 'dict' });
    }
  } catch (e) {
    console.warn('[Phonetix] disambiguate failed:', e);
  }
  return out;
}

async function applyTransforms(nodes: Text[], ipaMap: PhonemeResult, lang: Language, voice: string) {
  const overrides = await computeHomographOverrides(nodes, lang, voice);
  // Transform in batches, yielding between them, so a huge page (100k+ nodes)
  // stays responsive instead of freezing the tab in one synchronous pass.
  const BATCH = 400;
  for (let i = 0; i < nodes.length; i += BATCH) {
    const end = Math.min(i + BATCH, nodes.length);
    for (let j = i; j < end; j++) transformNode(nodes[j], ipaMap, lang, overrides.get(nodes[j]));
    if (end < nodes.length) await new Promise((r) => setTimeout(r, 0));
  }
}

function transformNode(node: Text, ipaMap: PhonemeResult, lang: Language, override?: Map<number, ResolvedIpa>) {
  const text = node.nodeValue;
  if (!text) return;

  const segs = segment(text, lang, MAX_WORD_LENGTH);

  let hasAny = !!(override && override.size > 0);
  if (!hasAny) {
    for (const s of segs) { if (s.isWord && ipaMap[s.text.toLowerCase()]) { hasAny = true; break; } }
  }
  if (!hasAny) return;

  const frag = document.createDocumentFragment();
  let wordIdx = 0;
  let carried = 0;   // characters of the next segment already taken as a trailing mark
  for (let i = 0; i < segs.length; i++) {
    const s = segs[i];
    if (s.isWord) {
      // Homograph override (context-resolved) wins over the context-free dict/espeak result.
      const r = override?.get(wordIdx) ?? ipaMap[s.text.toLowerCase()];
      wordIdx++;
      if (!r) {
        frag.appendChild(document.createTextNode(s.text));
        continue;
      }
      const next = segs[i + 1];
      const mark = next && !next.isWord ? (next.text.match(TRAILING_MARK)?.[0] ?? '') : '';
      carried = mark.length;
      frag.appendChild(createPhoneticSpan(s.text, r, mark));
    } else if (s.text) {
      const rest = s.text.slice(carried);
      carried = 0;
      if (rest) frag.appendChild(document.createTextNode(rest));
    }
  }
  node.parentNode?.replaceChild(frag, node);
}

// =====================================================================
//  Language detection
// =====================================================================

async function detectPageLanguage(): Promise<Language> {
  const htmlLang = document.documentElement.lang?.split('-')[0]?.toLowerCase();
  if (htmlLang && htmlLang in Languages) return htmlLang as Language;

  const sample = extractTextSample();
  if (sample.length < 20) return 'en';
  try { return await sendMessage('detectLanguage', sample); }
  catch { return 'en'; }
}

function extractTextSample(): string {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const p = node.parentElement;
      if (!p) return NodeFilter.FILTER_SKIP;
      if (BLOCKED_TAGS.has(p.tagName)) return NodeFilter.FILTER_SKIP;
      if (!node.nodeValue?.trim()) return NodeFilter.FILTER_SKIP;
      return NodeFilter.FILTER_ACCEPT;
    },
  });
  let text = '', n: Node | null;
  while ((n = walker.nextNode()) && text.length < 1000) text += ' ' + (n.nodeValue?.trim() || '');
  return text.trim();
}

// =====================================================================
//  Style injection & mode switching
// =====================================================================

function injectStyles() {
  if (document.getElementById('phonetix-styles')) return;
  const s = document.createElement('style');
  s.id = 'phonetix-styles';
  s.textContent = PHONETIX_CSS;
  document.head.appendChild(s);
}

function setMode(m: Mode) {
  const html = document.documentElement;
  for (const c of Object.values(MODE_CLASSES)) html.classList.remove(c);
  html.classList.add(MODE_CLASSES[m]);
}

function clearMode() {
  for (const c of Object.values(MODE_CLASSES)) document.documentElement.classList.remove(c);
}

// =====================================================================
//  Revert
// =====================================================================

function revertAll() {
  hideTooltip(true);
  for (const span of document.querySelectorAll(`.${PHONETIX_CLASS}`)) {
    const orig = (span as HTMLElement).dataset.original || '';
    span.parentNode?.replaceChild(document.createTextNode(orig), span);
  }
  document.body.normalize();
}

// =====================================================================
//  MutationObserver
// =====================================================================

let observer: MutationObserver | null = null;

const pendingRoots = new Set<Element>();
let flushTimer: ReturnType<typeof setTimeout> | null = null;

function observeDOM() {
  if (observer) return;
  observer = new MutationObserver((muts) => {
    for (const m of muts) {
      for (const n of m.addedNodes) {
        const el = n instanceof Element ? n : (n instanceof Text ? n.parentElement : null);
        if (el && !el.closest(`.${PHONETIX_CLASS}`)) pendingRoots.add(el);
      }
    }
    if (pendingRoots.size > 0 && !flushTimer) {
      flushTimer = setTimeout(flushRoots, 300);
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });
}

// Process only the added subtrees, not the whole page — dynamic pages (feeds,
// infinite scroll) add nodes constantly, and re-scanning the full document each
// time would make scrolling janky.
async function flushRoots() {
  flushTimer = null;
  const roots = [...pendingRoots];
  pendingRoots.clear();
  const tops = roots.filter((r) => r.isConnected && !roots.some((o) => o !== r && o.contains(r)));
  for (const r of tops) {
    try { await processPage(r); } catch (e) { console.warn('[Phonetix] DOM observer error:', e); }
  }
}

function stopObserver() {
  if (observer) { observer.disconnect(); observer = null; }
}

// =====================================================================
//  Entry point
// =====================================================================

export default defineContentScript({
  matches: ['<all_urls>'],
  runAt: 'document_idle',

  async main() {
    // Load saved state
    try {
      const extState = await storage.getItem<string>('local:extension_enabled');
      isEnabled = extState ? JSON.parse(extState) : true;

      const hostname = window.location.hostname;
      const websiteState = await storage.getItem<string>('local:websites_enabled');
      const websites: Record<string, boolean> = websiteState ? JSON.parse(websiteState) : {};
      if (hostname in websites && !websites[hostname]) isEnabled = false;

      const savedLang = await storage.getItem<string>('local:selectedLanguage');
      if (savedLang) languageOption = savedLang as LanguageOption;

      const savedAccents = await storage.getItem<string>('local:accents');
      if (savedAccents) accents = JSON.parse(savedAccents);

      hideStress = (await storage.getItem<string>('local:hideStress')) === 'true';

      const savedMode = await storage.getItem<string>('local:selectedMode');
      if (savedMode && savedMode in MODE_CLASSES) mode = savedMode as Mode;

      if (languageOption === 'auto') {
        pageLang = await detectPageLanguage();
        await storage.setItem('local:detectedLanguage', pageLang);
      } else {
        pageLang = languageOption as Language;
      }
    } catch (e) {
      console.warn('[Phonetix] Failed to load settings:', e);
    }

    injectStyles();
    initTooltip();
    setupTooltipEvents();

    if (isEnabled) {
      setMode(mode);
      await processPage();
      observeDOM();
    }

    // Test hook: on ?pxhealth pages, publish a subsystem probe to the DOM so the
    // integration suite can assert nothing silently degraded (as eld once had).
    if (location.search.includes('pxhealth')) {
      sendMessage('getHealth', {})
        .then((h) => { document.documentElement.dataset.pxhealth = JSON.stringify(h); })
        .catch((e) => { document.documentElement.dataset.pxhealth = JSON.stringify({ error: String(e) }); });
    }

    // Settings are watched, not delivered.
    //
    // The popup used to message the active tab. That reaches one tab, so every
    // other open page kept the old setting, and it depends on the popup agreeing
    // with the browser about which tab is active — which is exactly the kind of
    // thing that works on one browser and quietly does nothing on another.
    // Storage is the state; each page reacts to it changing, wherever it is.
    storage.watch<string>('local:accents', async (value) => {
      accents = value ? JSON.parse(value) : {};
      await reprocess();
    });

    storage.watch<string>('local:hideStress', async (value) => {
      hideStress = value === 'true';
      await reprocess();
    });

    storage.watch<string>('local:selectedMode', (value) => {
      if (!value || !(value in MODE_CLASSES)) return;
      mode = value as Mode;
      if (isEnabled) setMode(mode);
    });

    storage.watch<string>('local:selectedLanguage', async (value) => {
      languageOption = (value || 'auto') as LanguageOption;
      if (languageOption === 'auto') {
        pageLang = await detectPageLanguage();
        await storage.setItem('local:detectedLanguage', pageLang);
      } else {
        pageLang = languageOption as Language;
      }
      await reprocess();
    });

    /** On for this page: the site's own setting if it has one, else the default. */
    async function enabledHere(): Promise<boolean> {
      const extState = await storage.getItem<string>('local:extension_enabled');
      const byDefault = extState ? JSON.parse(extState) : true;
      const websiteState = await storage.getItem<string>('local:websites_enabled');
      const sites: Record<string, boolean> = websiteState ? JSON.parse(websiteState) : {};
      const host = window.location.hostname;
      return host in sites ? sites[host] : byDefault;
    }

    async function applyEnabled(): Promise<void> {
      const on = await enabledHere();
      if (on === isEnabled) return;
      isEnabled = on;
      if (on) {
        setMode(mode);
        await processPage();
        observeDOM();
      } else {
        revertAll();
        clearMode();
        stopObserver();
      }
    }

    storage.watch<string>('local:extension_enabled', applyEnabled);
    storage.watch<string>('local:websites_enabled', applyEnabled);
  },
});
