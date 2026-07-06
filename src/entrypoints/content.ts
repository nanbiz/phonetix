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
import { DefaultAccents, Languages, WiktionaryAnchors, BLOCK_TAGS } from '@/lib/types';
import type { LanguageOption, Mode, ResolvedIpa, PhonemeResult } from '@/lib/types';

type Language = string;
import { IPA_SYMBOLS, tokenizeIPA, wikimediaAudioURL } from '@/lib/ipa-symbols';
import { segment, words as wordsOf } from '@/lib/segment';

// =====================================================================
//  Module state
// =====================================================================

let isEnabled = true;
let languageOption: LanguageOption = 'auto';
let pageLang: Language = 'en';
let accent = 'en';
let mode: Mode = 'wholePage';

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
const ICO_WIKT = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path fill="currentColor" d="M2.22 18.6v.01c-.35-.21-.61-.5-.71-.84l-.07-.37L.21 3.36c-.03-.45.17-.9.57-1.25c.39-.36.97-.6 1.62-.66L15.35.22a2.8 2.8 0 0 1 1.7.35a1.5 1.5 0 0 1 .77 1.13l1.23 14.12c.03.45-.17.9-.57 1.25a2.85 2.85 0 0 1-1.62.67L3.92 18.95a2.75 2.75 0 0 1-1.7-.35m-1-1.1c.02.18.07.35.15.5l.02.25c.05.56.4 1.03.9 1.34c.51.3 1.19.46 1.9.4l13.34-1.27a3.15 3.15 0 0 0 1.8-.74c.45-.4.71-.93.66-1.49L18.73 1.87a1.77 1.77 0 0 0-.9-1.33a2.9 2.9 0 0 0-1.24-.4a3.2 3.2 0 0 0-1.27-.12L2.4 1.23a3.1 3.1 0 0 0-1.74.72c-.44.39-.7.9-.64 1.44l1.22 14.1zm1.2 1.9a1.6 1.6 0 0 1-.78-1a2 2 0 0 0 .47.39c.49.3 1.14.44 1.84.38l12.93-1.22c.7-.06 1.31-.33 1.74-.72c.44-.38.7-.9.64-1.43L18.04 1.69a1.62 1.62 0 0 0-.62-1.11c.1.04.2.09.29.15c.46.28.76.7.8 1.16l1.26 14.62c.04.48-.18.94-.59 1.3c-.4.37-1 .63-1.67.7L4.17 19.75a2.9 2.9 0 0 1-1.76-.36ZM1.21 5.3l4.34-.5l.06.47l-.28.04c-.28.03-.48.12-.6.26a.57.57 0 0 0-.15.46a12 12 0 0 0 .53 1.33l2.91 6.12l1.15-5.56l-.8-1.68c-.16-.27-.31-.5-.48-.7a1 1 0 0 0-.28-.23a1.4 1.4 0 0 0-.42-.17c-.1-.02-.25-.02-.48 0l-.08.02l-.06-.48l4.56-.53l.06.48l-.38.04c-.3.04-.5.13-.6.26a.67.67 0 0 0-.13.53c0 .02 0 .06.03.15l.15.4l3.2 6.84l1.32-6.48c.16-.75.22-1.25.18-1.51a.57.57 0 0 0-.14-.32a.57.57 0 0 0-.3-.18c-.2-.05-.47-.06-.8-.02h-.08l-.06-.48l3.53-.4l.06.48h-.08c-.29.04-.5.12-.66.24c-.15.12-.3.33-.42.64c-.08.2-.2.7-.35 1.5l-2.01 9.9l-.45.05l-3.4-7.06l-1.61 7.64l-.42.04l-4.56-9.42q-.51-1.05-.63-1.23a1 1 0 0 0-.47-.4a1.6 1.6 0 0 0-.76-.07h-.08Z"/></svg>';

// =====================================================================
//  Audio playback
// =====================================================================

let currentAudio: HTMLAudioElement | null = null;

function stopAudio(): void {
  if (currentAudio) { currentAudio.pause(); currentAudio = null; }
}

/** Play audio from a direct URL (Wiktionary/Wikimedia). */
function playUrl(url: string): void {
  stopAudio();
  currentAudio = new Audio(url);
  currentAudio.volume = 0.8;
  currentAudio.play().catch(() => {});
}

/** Play IPA symbol audio from Wikimedia Commons. */
function playSymbol(filename: string): void {
  playUrl(wikimediaAudioURL(filename));
}

/**
 * Speak a word via espeak-ng synthesis in the offscreen document.
 * Uses the actual espeak voice engine — pronounces IPA, not just
 * reading raw text like speechSynthesis would.
 */
function speakWord(word: string, lang: Language): void {
  stopAudio();
  const voice = DefaultAccents[lang] || lang;
  if (import.meta.env.BROWSER === 'firefox') {
    // Firefox background can't play audio (no user gesture); play the WAV here.
    sendMessage('synthesizeAudio', { word, voice })
      .then((bytes) => {
        if (!bytes.length) return;
        const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: 'audio/wav' }));
        playUrl(url);
        // playUrl replaces currentAudio; revoke once it can start.
        currentAudio?.addEventListener('ended', () => URL.revokeObjectURL(url), { once: true });
      })
      .catch((e) => console.warn('[Phonetix] synthesizeAudio failed:', e));
  } else {
    sendMessage('speakWord', { word, voice });
  }
}

// =====================================================================
//  Tooltip – DOM references
// =====================================================================

let ttHost: HTMLDivElement | null = null;
let ttEl: HTMLDivElement | null = null;
let ttDetail: HTMLElement | null = null;
let hoverTimer: ReturnType<typeof setTimeout> | null = null;
let hideTimer: ReturnType<typeof setTimeout> | null = null;
let curTarget: HTMLElement | null = null;
let highlightedEl: HTMLElement | null = null;
let curTargetRect: DOMRect | null = null;
let ttAbove = false;
let ttVisible = false;

// =====================================================================
//  Tooltip – lifecycle
// =====================================================================

function initTooltip(): void {
  if (ttHost) return;
  ttHost = document.createElement('div');
  ttHost.id = 'phonetix-tooltip-host';
  ttHost.style.cssText = 'position:fixed;top:0;left:0;width:0;height:0;z-index:2147483647;pointer-events:none;overflow:visible;';
  document.body.appendChild(ttHost);

  const shadow = ttHost.attachShadow({ mode: 'closed' });
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

  // Dismiss on click/scroll
  document.addEventListener('click', () => { if (ttVisible) { clearTimers(); hideTooltip(); curTarget = null; } });
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
  const ipaSpan = target.querySelector(`.${IPA_CLASS}`);
  const ipa = ipaSpan?.textContent || '';
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
  let top: number;
  if (rect.top > th + 12) {
    top = rect.top - th - 8;
    ttAbove = true;
    ttEl.classList.add('above');
  } else {
    top = rect.bottom + 8;
    ttAbove = false;
    ttEl.classList.add('below');
  }
  const cx = rect.left + rect.width / 2;
  let left = Math.max(8, Math.min(cx - tw / 2, window.innerWidth - tw - 8));
  ttEl.style.setProperty('--arrow-left', `${Math.max(12, Math.min(cx - left, tw - 12))}px`);
  ttEl.style.top = `${top}px`;
  ttEl.style.left = `${left}px`;

  ttVisible = true;
  requestAnimationFrame(() => ttEl?.classList.add('visible'));
}

function hideTooltip(): void {
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
  r1.appendChild(txt('span', 'px-lang', lang.toUpperCase()));
  r1.appendChild(txt('span', `px-src px-src-${src}`, src === 'espeak' ? 'espeak' : 'dict'));
  r1.appendChild(el('div', 'px-spacer'));

  const wiktBtn = el('a', 'px-btn disabled') as HTMLAnchorElement;
  wiktBtn.innerHTML = ICO_WIKT;
  wiktBtn.title = 'Wiktionary';
  wiktBtn.target = '_blank';
  wiktBtn.rel = 'noopener noreferrer';
  wiktBtn.href = wiktionaryURL(lang, word);
  r1.appendChild(wiktBtn);

  // Wiktionary recording button — enables only if a recording exists.
  const audioBtn = btn('px-btn disabled', ICO_SPEAKER, 'Loading…');
  r1.appendChild(audioBtn);
  ttEl.appendChild(r1);

  // ── Row 2: primary /ipa/ (matches the page) + speak in the source language ──
  const r2 = el('div', 'px-r2');
  r2.appendChild(txt('span', 'px-ipa-text', `/${ipa}/`));
  const ttsBtn = btn('px-btn px-btn-sm', ICO_SPEAKER_SM, `Speak (${DefaultAccents[lang] || lang})`);
  ttsBtn.addEventListener('click', (e) => { e.stopPropagation(); speakWord(word, lang); });
  r2.appendChild(ttsBtn);
  ttEl.appendChild(r2);

  // ── Symbol breakdown (of the primary IPA; never re-rendered by async data) ──
  const symbolsContainer = el('div', 'px-symbols-wrap');
  ttEl.appendChild(symbolsContainer);
  renderSymbols(symbolsContainer, ipa);

  // Reserved row for a Wiktionary alternative — a labeled addition, never a
  // silent replacement of the primary shown on the page.
  const altRow = el('div', 'px-alt');
  altRow.style.display = 'none';
  ttEl.appendChild(altRow);

  sendMessage('checkWiktionary', { lang, word }).then((info) => {
    const displayLang = info.wordLang || (info.foundLang as string) || lang;
    const linkLang = info.foundLang || lang;

    if (info.exists && info.matchedTitle) {
      wiktBtn.classList.remove('disabled');
      wiktBtn.href = wiktionaryURL(linkLang, info.matchedTitle);
    }

    if (info.wiktIpa && info.wiktIpa !== ipa) {
      altRow.style.display = '';
      altRow.appendChild(txt('span', 'px-alt-tag', `Wiktionary ${displayLang.toUpperCase()}`));
      altRow.appendChild(txt('span', 'px-alt-ipa', `/${info.wiktIpa}/`));
    }

    if (info.audioUrl) {
      audioBtn.classList.remove('disabled');
      audioBtn.title = 'Wiktionary recording';
      audioBtn.addEventListener('click', (e) => { e.stopPropagation(); playUrl(info.audioUrl!); });
    } else {
      audioBtn.title = 'No Wiktionary recording';
    }
  }).catch(() => { audioBtn.title = 'No Wiktionary recording'; });
}

/** Build (or rebuild) the IPA symbol grid + detail + legend into a container. */
function renderSymbols(container: HTMLElement, ipa: string): void {
  container.innerHTML = '';
  const tokens = tokenizeIPA(ipa);
  if (tokens.length > 0) {
    const grid = el('div', 'px-symbols');
    const detail = el('div', 'px-detail');
    detail.appendChild(txt('div', 'px-detail-empty', 'Hover a symbol for details'));
    ttDetail = detail;

    for (const tok of tokens) {
      if (!tok.trim()) continue;
      const info = IPA_SYMBOLS[tok] || IPA_SYMBOLS[tok[0]];
      const cls = info ? TYPE_CLASS[info.type] || '' : '';
      const hasAudio = !!info?.audio;

      const sym = el('div', `px-sym ${cls} ${hasAudio ? 'clickable' : ''}`);
      sym.appendChild(txt('span', 'px-sym-ch', tok));

      if (hasAudio) {
        const spk = el('span', 'px-sym-spk');
        spk.innerHTML = ICO_SPEAKER_SM;
        sym.appendChild(spk);
        const file = info!.audio!;
        sym.addEventListener('click', (e) => { e.stopPropagation(); playSymbol(file); });
      }

      sym.addEventListener('mouseenter', () => {
        if (!info || !ttDetail) return;
        ttDetail.innerHTML = '';
        ttDetail.appendChild(txt('div', 'px-detail-name', info.name));
        ttDetail.appendChild(txt('div', 'px-detail-eg', info.example));
      });
      sym.addEventListener('mouseleave', () => {
        if (!ttDetail) return;
        ttDetail.innerHTML = '';
        ttDetail.appendChild(txt('div', 'px-detail-empty', 'Hover a symbol for details'));
      });

      grid.appendChild(sym);
    }
    container.appendChild(grid);
    container.appendChild(detail);
  }

  const footer = el('div', 'px-footer');
  for (const [c, label] of [['C', 'consonant'], ['V', 'vowel'], ['S', 'stress']] as const) {
    const leg = el('span', 'px-leg');
    leg.appendChild(el('span', `px-dot ${c}`));
    leg.appendChild(document.createTextNode(label));
    footer.appendChild(leg);
  }
  footer.appendChild(el('div', 'px-spacer'));
  container.appendChild(footer);
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
function createPhoneticSpan(original: string, r: ResolvedIpa): HTMLSpanElement {
  const finalLang = extractIPALang(r.ipa) || r.lang;
  const finalIpa = cleanIPA(r.ipa);

  const span = document.createElement('span');
  span.className = PHONETIX_CLASS;
  span.dataset.original = original;
  span.dataset.lang = finalLang;   // resolution language (may differ from block for loanwords)
  span.dataset.src = r.src;        // 'dict' | 'espeak' — drives the tooltip source label

  const origSpan = document.createElement('span');
  origSpan.className = ORIG_CLASS;
  origSpan.textContent = original;

  const ipaSpan = document.createElement('span');
  ipaSpan.className = IPA_CLASS;
  ipaSpan.textContent = finalIpa; // CLEAN — tooltip reads this directly

  span.appendChild(origSpan);
  span.appendChild(ipaSpan);
  return span;
}

// =====================================================================
//  Page processing
// =====================================================================

interface TextBlock { blockElement: Element; textNodes: Text[]; text: string; }

async function processPage(): Promise<void> {
  if (languageOption === 'auto') {
    await processMultilingual();
  } else {
    const nodes = collectTextNodes(document.body);
    const words = uniqueWords(nodes, pageLang);
    if (words.length === 0) return;

    const ipaMap = await sendMessage('phonemize', { words, voice: accent, lang: pageLang });
    await applyTransforms(nodes, ipaMap, pageLang, accent);
  }
}

async function processMultilingual(): Promise<void> {
  const blocks = groupByBlock(document.body);
  if (blocks.length === 0) return;

  const blockLangs = await detectBlockLanguages(blocks);

  // Track most common language for popup display
  const counts = new Map<Language, number>();
  for (const l of blockLangs) counts.set(l, (counts.get(l) || 0) + 1);
  let best = pageLang, bestN = 0;
  for (const [l, n] of counts) { if (n > bestN) { best = l; bestN = n; } }
  pageLang = best;
  await storage.setItem('local:detectedLanguage', pageLang);

  // Group by voice → phonemize + Wiktionary batch in parallel
  const byVoice = new Map<string, { nodes: Text[]; words: Set<string>; lang: Language }>();
  for (let i = 0; i < blocks.length; i++) {
    const lang = blockLangs[i];
    const voice = DefaultAccents[lang];
    if (!byVoice.has(voice)) byVoice.set(voice, { nodes: [], words: new Set(), lang });
    const g = byVoice.get(voice)!;
    for (const tn of blocks[i].textNodes) {
      g.nodes.push(tn);
      for (const w of wordsOf(tn.nodeValue || '', lang, MAX_WORD_LENGTH)) g.words.add(w);
    }
  }

  await Promise.all([...byVoice.entries()].map(async ([voice, g]) => {
    const words = [...g.words];
    if (words.length === 0) return;

    const ipaMap = await sendMessage('phonemize', { words, voice, lang: g.lang });
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
  const toDetect: string[] = [];
  const indices: number[] = [];

  for (let i = 0; i < blocks.length; i++) {
    const explicit = findExplicitLang(blocks[i].blockElement);
    if (explicit) { results[i] = explicit; continue; }
    if (blocks[i].text.length < 40) continue;
    toDetect.push(blocks[i].text);
    indices.push(i);
  }

  if (toDetect.length > 0) {
    try {
      const detected = await sendMessage('detectLanguages', { texts: toDetect });
      for (let j = 0; j < detected.length; j++) {
        if (detected[j] !== null) results[indices[j]] = detected[j] as Language;
      }
    } catch (e) {
      console.warn('[Phonetix] Block language detection failed:', e);
    }
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
  for (const s of segs) {
    if (s.isWord) {
      // Homograph override (context-resolved) wins over the context-free dict/espeak result.
      const r = override?.get(wordIdx) ?? ipaMap[s.text.toLowerCase()];
      wordIdx++;
      frag.appendChild(r ? createPhoneticSpan(s.text, r) : document.createTextNode(s.text));
    } else if (s.text) {
      frag.appendChild(document.createTextNode(s.text));
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
  hideTooltip();
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

function observeDOM() {
  if (observer) return;
  let pending = false;
  observer = new MutationObserver((muts) => {
    if (pending) return;
    let found = false;
    for (const m of muts) {
      for (const n of m.addedNodes) {
        if ((n instanceof Element && !n.classList?.contains(PHONETIX_CLASS)) ||
            (n instanceof Text && n.nodeValue?.trim())) {
          found = true; break;
        }
      }
      if (found) break;
    }
    if (!found) return;
    pending = true;
    setTimeout(async () => {
      try { await processPage(); } catch (e) { console.warn('[Phonetix] DOM observer error:', e); }
      pending = false;
    }, 300);
  });
  observer.observe(document.body, { childList: true, subtree: true });
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

      const savedAccent = await storage.getItem<string>('local:selectedAccent');
      const savedMode = await storage.getItem<string>('local:selectedMode');
      if (savedMode && savedMode in MODE_CLASSES) mode = savedMode as Mode;

      if (languageOption === 'auto') {
        pageLang = await detectPageLanguage();
        accent = DefaultAccents[pageLang];
        await storage.setItem('local:detectedLanguage', pageLang);
      } else {
        pageLang = languageOption as Language;
        accent = savedAccent || DefaultAccents[pageLang];
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

    // Popup messages
    onMessage('extensionToggled', async (msg) => {
      const on = msg.data;
      if (on && !isEnabled) { isEnabled = true; setMode(mode); await processPage(); observeDOM(); }
      else if (!on && isEnabled) { isEnabled = false; revertAll(); clearMode(); stopObserver(); }
    });

    onMessage('modeChanged', (msg) => { mode = msg.data; if (isEnabled) setMode(mode); });

    onMessage('languageChanged', async (msg) => {
      languageOption = msg.data;
      if (languageOption === 'auto') {
        pageLang = await detectPageLanguage();
        accent = DefaultAccents[pageLang];
        await storage.setItem('local:detectedLanguage', pageLang);
      } else {
        pageLang = languageOption as Language;
        accent = DefaultAccents[pageLang];
      }
      if (isEnabled) { stopObserver(); revertAll(); await processPage(); observeDOM(); }
    });

    onMessage('accentChanged', async (msg) => {
      accent = msg.data;
      if (isEnabled) { stopObserver(); revertAll(); await processPage(); observeDOM(); }
    });
  },
});
