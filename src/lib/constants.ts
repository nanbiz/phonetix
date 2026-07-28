export const BLOCKED_TAGS = new Set([
  'SCRIPT', 'STYLE', 'NOSCRIPT', 'TEXTAREA', 'INPUT', 'SELECT',
  'CODE', 'PRE', 'KBD', 'SAMP', 'VAR', 'SVG', 'MATH', 'CANVAS',
  'VIDEO', 'AUDIO', 'IFRAME', 'OBJECT', 'EMBED',
]);

/** Structural UI chrome to skip: navigation, controls, and boilerplate landmarks.
 *  Rule-based (semantic tags + ARIA roles), never a word list. Article body,
 *  headings and <main> are deliberately kept. */
export const CHROME_SELECTOR =
  'nav,footer,button,select,summary,' +
  '[role="navigation"],[role="menu"],[role="menubar"],[role="toolbar"],' +
  '[role="tablist"],[role="tab"],[role="banner"],[role="contentinfo"],' +
  '[role="search"],[role="button"],[aria-hidden="true"]';

export const PHONETIX_CLASS = 'phonetix';
export const ORIG_CLASS = 'px-orig';
export const IPA_CLASS = 'px-ipa';

export const MODE_CLASSES = {
  showOriginalOnHover: 'px-mode-reveal',
  onHover: 'px-mode-hover',
  // Sprinkle shows the IPA inline with the original on hover, exactly like reveal;
  // it differs only in transcribing a sparse, high-confidence subset of the words.
  sprinkle: 'px-mode-reveal',
} as const;

/** Regex matching Latin-script words (including accented chars for DE/ES/FR) */
export const WORD_RE = /[a-zA-Z\u00C0-\u024F\u1E00-\u1EFF]+/g;

export const MAX_WORD_LENGTH = 50;

/** Patterns that mark a text node as technical (URLs, IPs, base64, etc.) — skip entirely. */
export const TECHNICAL_RE = /(:\/\/|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|\d{4}-\d{2}-\d{2}T|\w+\.\w{2,}[\/\?#])/;

/** CSS injected into pages for display mode switching */
export const PHONETIX_CSS = `
/* Base styles */
.${PHONETIX_CLASS} { display: inline; text-decoration: inherit; color: inherit; font: inherit; border-radius: 2px; transition: background .15s; }
/* Over a hovered word the pointer sits on the very IPA it reveals, so it shrinks to
   a small dot: a filled centre with a thin light outline, visible on any background
   while covering as little of the transcription as possible. */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover,
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover {
  cursor: url('data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2218%22 height=%2218%22%3E%3Ccircle cx=%229%22 cy=%229%22 r=%223.5%22 fill=%22rgba(0,0,0,0.5)%22 stroke=%22rgba(255,255,255,0.7)%22 stroke-width=%221.5%22/%3E%3C/svg%3E') 9 9, default;
}

.${PHONETIX_CLASS} .${IPA_CLASS} { display: none; }
.${PHONETIX_CLASS} .${ORIG_CLASS} { display: inline; }

/*
 * Hover modes show one layer as ordinary inline text; the other is display:none, so
 * the running text lays out exactly as the page would without us — no reserved
 * width, no reflow, and text-overflow ellipsis works because the span is inline.
 * On hover the hidden layer is shown in place over the word (the .px-hover rule).
 */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} .${ORIG_CLASS} { display: inline; }
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} .${IPA_CLASS} { display: none; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} .${IPA_CLASS} { display: inline; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} .${ORIG_CLASS} { display: none; }

/* On hover the word's other layer is shown in place, over the word itself: an
   absolute box anchored at the word's own origin (top/left 0), so it lands on the
   exact pixels in every browser with no measurement to drift. The hovered word is
   inline-block only so it is its own containing block — an absolute child of a plain
   inline element is placed at the paragraph top by Firefox. min-width keeps the box
   covering the whole word even when the other form is shorter, so no part of the
   word peeks out around it; the page-coloured background (set per word in content.ts)
   hides the word beneath. Same font and line box as the word, so nothing shifts. */
.px-hover { position: relative; display: inline-block; }
.${MODE_CLASSES.onHover} .px-hover .${IPA_CLASS},
.${MODE_CLASSES.showOriginalOnHover} .px-hover .${ORIG_CLASS} {
  display: inline-block;
  position: absolute;
  /* Centred on the word's own slot: the box is at least as wide as the word
     (min-width:100%), centred over it (left:50% + translateX(-50%)), and its text is
     centred inside. So the revealed form — whether it is narrower than the word (an
     original over its wider IPA) or wider (IPA over its word) — sits centred in the
     same space, which is where the tooltip's arrow points. The padding keeps a wider
     form from butting into the neighbouring words. */
  box-sizing: content-box;
  left: 50%;
  top: 0;
  transform: translateX(-50%);
  padding: 0 4px;
  min-width: 100%;
  text-align: center;
  white-space: nowrap;
  color: inherit;
  background: var(--px-bg, #fff);
  border-radius: 3px;
  z-index: 5;
}

@keyframes px-reveal-in { from { opacity: 0; } to { opacity: 1; } }
[data-px-anim="on"] .px-hover .${IPA_CLASS},
[data-px-anim="on"] .px-hover .${ORIG_CLASS} { animation: px-reveal-in .12s ease-out; }
`.trim();

/** CSS for the tooltip (injected into Shadow DOM) */
export const TOOLTIP_CSS = `
* { box-sizing: border-box; margin: 0; padding: 0; }

.px-tt {
  position: fixed;
  display: none;
  /* A fixed box. The symbol descriptions differ in length, and a tooltip that
     grew with them would move under the cursor every time one is read. */
  width: 340px;
  padding: 14px 16px;
  background: #1c1c1e;
  border: 1px solid #333;
  border-radius: 10px;
  box-shadow: 0 6px 20px rgba(0,0,0,.5);
  font: 13px/1.4 -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
  color: #d1d5db;
  opacity: 0;
  transform: translateY(6px) scale(.985);
  pointer-events: auto;
}
/* The entrance runs only when animations are on; off, it appears in place. */
.px-tt[data-px-anim="on"] { transition: opacity .15s ease, transform .18s cubic-bezier(.2,.8,.2,1); }
.px-tt.visible { opacity: 1; transform: translateY(0) scale(1); }

/* Arrow */
.px-tt::before {
  content: '';
  position: absolute;
  left: var(--arrow-left, 50%);
  width: 8px; height: 8px;
  background: #1c1c1e;
  border: 1px solid #333;
  transform: translateX(-50%) rotate(45deg);
}
.px-tt.above::before { bottom: -5px; border-top: 0; border-left: 0; }
.px-tt.below::before { top: -5px; border-bottom: 0; border-right: 0; }

/* ── Row 1: word + actions ── */
.px-r1 {
  display: flex;
  align-items: center;
  gap: 8px;
}
.px-word {
  font-size: 16px;
  font-weight: 600;
  color: #f5f5f7;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.px-lang {
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .4px;
  color: #b7b7c0;
  background: rgba(255,255,255,.07);
  padding: 2px 7px;
  border-radius: 999px;
  line-height: 1.5;
  white-space: nowrap;
}
.px-src {
  font-size: 9px;
  font-weight: 500;
  padding: 2px 7px;
  border-radius: 999px;
  line-height: 1.5;
  text-transform: lowercase;
  letter-spacing: .3px;
}
.px-src-dict { color: #7fd7a8; background: rgba(93,232,176,.14); }
.px-src-espeak { color: #e0b978; background: rgba(224,185,120,.14); }
.px-spacer { flex: 1; }

/* Buttons */
.px-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: 0;
  color: #666;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: color .1s, background .1s;
  flex-shrink: 0;
}
.px-btn:hover { color: #e5e5e5; background: rgba(255,255,255,.08); }
.px-btn:active { transform: scale(.92); }
.px-btn.disabled { opacity: .25; pointer-events: none; }
.px-btn svg { width: 16px; height: 16px; display: block; }
.px-btn-sm svg { width: 12px; height: 12px; }
/* Beside the pronunciation it speaks, not across the card from it. */
.px-btn-tts { margin-left: 6px; }
.px-btn-tts svg { width: 18px; height: 18px; }

/* ── The pronunciation, which is also the interactive part ── */
.px-r2 {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 10px 0 12px;
}
.px-slash {
  font: 22px/1 'Gentium Plus', 'Doulos SIL', 'Charis SIL', 'Noto Sans', serif;
  color: #4a4a52;
}
.px-ipa-line {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 1px;
}

/* Each symbol is the text itself, not a box repeating it. */
/* The symbol underlines itself, rather than carrying a rule beneath its box: the
   underline follows the letter, and its colour says what kind of sound it is. */
.px-sym {
  font: 500 24px/1.25 'Gentium Plus', 'Doulos SIL', 'Charis SIL', 'Noto Sans', serif;
  color: #eaeaf0;
  text-decoration: underline;
  text-decoration-color: transparent;
  text-decoration-thickness: 2px;
  text-underline-offset: 4px;
  padding: 2px 2px 3px;
  border-radius: 4px;
  cursor: default;
  transition: background .08s, color .08s;
}
.px-sym:hover { background: rgba(255,255,255,.10); }
.px-sym.active { background: rgba(109,159,255,.18); color: #fff; }
.px-sym.clickable { cursor: pointer; }
.px-sym.clickable:active { transform: scale(.94); }
.px-sym.C { text-decoration-color: #6d9fff; }
.px-sym.V { text-decoration-color: #ff6d8f; }
.px-sym.S { text-decoration-color: #b78dff; }
.px-sym.D { text-decoration-color: #5de8b0; }

/* ── Detail line: fixed height, so exploring never resizes the tooltip ── */
/* Fixed height, so reading a symbol never resizes the tooltip. It is the text that
   is clipped to it, not the line itself: clipping the line would cut off the
   enlarged diagram, which has to be able to grow out of the tooltip. */
.px-detail {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 46px;
  padding: 6px 8px;
  border-radius: 6px;
  background: #232327;
}
.px-detail-sym {
  font: 500 16px/1 'Gentium Plus', 'Doulos SIL', 'Charis SIL', 'Noto Sans', serif;
  color: #6d9fff;
  flex: none;
}
.px-detail-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
  overflow: hidden;
}
.px-detail-name {
  font-size: 11px;
  color: #d8d8de;
  font-weight: 500;
  /* The description is the point of the panel, so it wraps to a second line rather
     than being cut off with an ellipsis. */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.px-detail-name .px-detail-link { font-weight: 500; }
.px-detail-link {
  color: #9fc0ff;
  text-decoration: none;
  cursor: pointer;
}
.px-detail-link:hover { color: #cfe0ff; text-decoration: underline; }
.px-detail-eg {
  font-size: 11px;
  color: #7a7a82;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.px-detail-mri {
  flex: none;
  font-size: 9px;
  font-weight: 600;
  letter-spacing: .4px;
  color: #9fc0ff;
  text-decoration: none;
  padding: 3px 6px;
  border: 1px solid #33436b;
  border-radius: 999px;
}
.px-detail-mri:hover { color: #fff; background: #33436b; }

/* A sagittal section is dark ink drawn to be read on white, and some are filled
   silhouettes rather than thin lines, so inverting them onto the dark panel turned
   them into a near-invisible dark blob. They sit on a small white card instead,
   which is how such a diagram is meant to be seen. */
.px-detail-diagram {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 46px;
  height: 34px;
  border-radius: 4px;
  background: #fff;
  overflow: visible;
}
.px-detail-diagram:empty { background: none; }
.px-detail-diagram img {
  max-width: 100%;
  max-height: 100%;
  display: block;
  transition: transform .12s ease-out;
}

/* A 46px thumbnail shows that a diagram exists; it does not show a mouth. Hovering
   it grows the picture about its own centre, so it opens where the eye already is
   rather than jumping somewhere else to be read. The enlarged view carries its own
   dark ground so the light lines stay readable wherever it overflows to. */
.px-detail-diagram:hover {
  z-index: 10;
}
.px-detail-diagram:hover img {
  transform: scale(5);
  transform-origin: center center;
  background: #fff;
  border-radius: 2px;
  box-shadow: 0 4px 24px rgba(0,0,0,.45);
}

.px-detail-spk {
  margin-left: auto;
  color: #6d9fff;
  flex: none;
  padding: 6px;
}
.px-detail-spk:hover { color: #a9c8ff; background: rgba(109,159,255,.14); }
.px-detail-spk svg { width: 20px; height: 20px; display: block; }

/* ── Animations, all gated on the reader's setting ── */
/* The transcription's symbols rise in one after another as the tooltip opens. */
@keyframes px-sym-in { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
.px-tt[data-px-anim="on"] .px-sym { animation: px-sym-in .22s cubic-bezier(.2,.8,.2,1) both; }
.px-tt[data-px-anim="on"] .px-sym:nth-child(1) { animation-delay: .02s; }
.px-tt[data-px-anim="on"] .px-sym:nth-child(2) { animation-delay: .05s; }
.px-tt[data-px-anim="on"] .px-sym:nth-child(3) { animation-delay: .08s; }
.px-tt[data-px-anim="on"] .px-sym:nth-child(4) { animation-delay: .11s; }
.px-tt[data-px-anim="on"] .px-sym:nth-child(5) { animation-delay: .14s; }
.px-tt[data-px-anim="on"] .px-sym:nth-child(6) { animation-delay: .17s; }
.px-tt[data-px-anim="on"] .px-sym:nth-child(n+7) { animation-delay: .2s; }

/* Row 1, the description line and the pronunciation ease in under the symbols. */
@keyframes px-fade-up { from { opacity: 0; transform: translateY(3px); } to { opacity: 1; transform: none; } }
.px-tt[data-px-anim="on"] .px-r1,
.px-tt[data-px-anim="on"] .px-r2,
.px-tt[data-px-anim="on"] .px-detail { animation: px-fade-up .2s ease-out both; }
.px-tt[data-px-anim="on"] .px-detail { animation-delay: .06s; }

/* The description swaps softly as a new symbol is read. */
.px-tt[data-px-anim="on"] .px-detail-text { transition: opacity .1s ease; }

/* The diagram zoom only eases when animations are on; off, it snaps. */
.px-detail-diagram img { transition: none; }
.px-tt[data-px-anim="on"] .px-detail-diagram img { transition: transform .13s cubic-bezier(.2,.8,.2,1); }

/* Light mode: the tooltip follows the reader's system theme. Only the surfaces and
   ink flip; the type colours (blue links, coloured underlines, source tags) hold on
   both grounds. The diagrams are drawn dark, so in light mode they are not inverted
   and sit on a light card. */
@media (prefers-color-scheme: light) {
  .px-tt { background: #fff; border-color: #dcdce0; color: #33343a; box-shadow: 0 6px 20px rgba(0,0,0,.18); }
  .px-tt::before { background: #fff; border-color: #dcdce0; }
  .px-word { color: #16171b; }
  .px-lang { color: #6a6a72; }
  .px-slash { color: #b8b8c0; }
  .px-sym { color: #1c1d22; }
  .px-sym:hover { background: rgba(0,0,0,.06); }
  .px-sym.active { background: rgba(45,110,255,.14); color: #0b1c44; }
  .px-detail { background: #f1f1f4; }
  .px-detail-name { color: #33343a; }
  .px-detail-eg { color: #8a8a92; }
  .px-detail-empty { color: #9a9aa2; }
  .px-detail-diagram { background: #fff; }
  .px-btn { color: #8a8a92; }
  .px-btn:hover { color: #16171b; background: rgba(0,0,0,.06); }
}
`.trim();
