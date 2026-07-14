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
/* Over a hovered word the pointer sits on top of the very IPA it reveals, hiding a
   letter or two. It becomes a small hollow ring with a black-and-white outline:
   visible on any background, and the transcription shows through the hole. */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover,
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover {
  cursor: url('data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2218%22 height=%2218%22%3E%3Ccircle cx=%229%22 cy=%229%22 r=%225.5%22 fill=%22none%22 stroke=%22black%22 stroke-width=%223%22/%3E%3Ccircle cx=%229%22 cy=%229%22 r=%225.5%22 fill=%22none%22 stroke=%22white%22 stroke-width=%221.5%22/%3E%3C/svg%3E') 9 9, default;
}

.${PHONETIX_CLASS} .${IPA_CLASS} { display: none; }
.${PHONETIX_CLASS} .${ORIG_CLASS} { display: inline; }

/*
 * Hover modes show one layer and keep the other for the hover. Only the visible
 * layer may take up space: a box as wide as the wider of the two would pad every
 * word in the running text with the difference, which reads as broken spacing
 * ("is  a  branch of  linguistics"). The hidden layer is therefore taken out of
 * the flow, and the visible one alone sets the width.
 *
 * On hover the two swap by visibility only, so the box never changes size and the
 * line never reflows under the cursor. The revealed layer is allowed to overflow
 * its box, and carries a background so it stays readable over its neighbours.
 */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS},
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} {
  /* Plain inline, so the span is part of the running text: an inline-block is an
     atomic box that text-overflow ellipsis cannot break, so a truncated label
     ("5-hour limit") swallowed its last word into the ellipsis. Position relative
     on an inline element still makes it the containing block for the absolutely
     positioned hidden layer, which keeps that layer on its own word not the page. */
  position: relative;
  font: inherit;
  line-height: inherit;
}

/* onHover: the original is the running text; the IPA overlays it on hover. */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} .${ORIG_CLASS} { display: inline; visibility: inherit; }
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} .${IPA_CLASS} {
  display: inline;
  visibility: hidden;
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
  /* No top/bottom, so the layer keeps its inline static position vertically — the
     text baseline — while left:50% centres it on the word. On an inline containing
     block, top:0 would be the line-box top, dropping the layer below its own word.
     A truncated ancestor must not clip it to an ellipsis. */
  max-width: none;
  overflow: visible;
  text-overflow: clip;
}
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover .${ORIG_CLASS} { visibility: hidden; }
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover .${IPA_CLASS} {
  visibility: inherit;
  z-index: 2147483646;

}

/*
 * The revealed layer's surface is drawn behind the text, never on it.
 *
 * Padding or a border on the layer itself applies only while hovered, and a box that
 * gains padding on hover moves its own text: the word shifted down and sideways at
 * the moment of being read. Everything the surface needs — the backing colour, the
 * blur of what lies behind, the shadow — sits on a pseudo-element inset behind the
 * text, so hovering changes the paint and nothing about the box.
 *
 * backdrop-filter acts on the backdrop, never on the element's own text, so the
 * transcription stays sharp while the words under it recede. The blur reaches past
 * the text and fades out to both sides, so it has no edge of its own.
 */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover .${IPA_CLASS}::before,
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover .${ORIG_CLASS}::before {
  content: '';
  position: absolute;
  /* Tight to the text: the surface widened by 14px a side reached onto the words
     next to it even when the revealed layer was no wider than the word it replaced.
     It now clears the glyphs and no more, and the blur fades out within that. */
  inset: -1px -5px;
  z-index: -1;
  pointer-events: none;
  border-radius: 6px;
  background: color-mix(in srgb, Canvas 90%, transparent);
  backdrop-filter: blur(3px);
  box-shadow: 0 1px 6px rgba(0,0,0,.18);
  -webkit-mask-image: linear-gradient(to right, transparent 0, black 5px, black calc(100% - 5px), transparent 100%);
  mask-image: linear-gradient(to right, transparent 0, black 5px, black calc(100% - 5px), transparent 100%);
}

/* showOriginalOnHover: the IPA is the running text; the original overlays it. */
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} .${IPA_CLASS} { display: inline; visibility: inherit; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} .${ORIG_CLASS} {
  display: inline;
  visibility: hidden;
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
  max-width: none;
  overflow: visible;
  text-overflow: clip;
}
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover .${IPA_CLASS} { visibility: hidden; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover .${ORIG_CLASS} {
  visibility: inherit;
  z-index: 2147483646;

}
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
  transform: translateY(4px);
  transition: opacity .12s, transform .12s;
  pointer-events: auto;
}
.px-tt.visible { opacity: 1; transform: translateY(0); }

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

/* The diagrams are dark line drawings on a transparent ground. Inverting them makes
   the lines light so they sit on the tooltip's own dark background instead of a
   white box: the picture blends into the panel, and there is no white ground to
   flash through as it scales. */
.px-detail-diagram {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 46px;
  height: 34px;
  border-radius: 4px;
  background: #2c2c31;
  overflow: visible;
}
.px-detail-diagram:empty { background: none; }
.px-detail-diagram img {
  max-width: 100%;
  max-height: 100%;
  display: block;
  filter: invert(0.9) contrast(1.05);
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
  background: #1c1c1e;
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
  .px-detail-diagram { background: #ececef; }
  .px-detail-diagram img { filter: none; }
  .px-detail-diagram:hover img { background: #fff; }
  .px-btn { color: #8a8a92; }
  .px-btn:hover { color: #16171b; background: rgba(0,0,0,.06); }
}
`.trim();
