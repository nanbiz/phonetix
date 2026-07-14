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
  wholePage: 'px-mode-whole',
  onHover: 'px-mode-hover',
  showOriginalOnHover: 'px-mode-reveal',
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
.${PHONETIX_CLASS}.px-active { background: rgba(109,159,255,.12); }
.${PHONETIX_CLASS} .${IPA_CLASS} { display: none; }
.${PHONETIX_CLASS} .${ORIG_CLASS} { display: inline; }

/* wholePage: show IPA, hide original — no stacking needed */
.${MODE_CLASSES.wholePage} .${PHONETIX_CLASS} .${ORIG_CLASS} { display: none; }
.${MODE_CLASSES.wholePage} .${PHONETIX_CLASS} .${IPA_CLASS} { display: inline; }

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
  position: relative;
  display: inline;
}

/* onHover: the original is the running text; the IPA overlays it on hover. */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} .${ORIG_CLASS} { display: inline; visibility: inherit; }
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} .${IPA_CLASS} {
  display: inline;
  visibility: hidden;
  position: absolute;
  left: 0;
  top: 0;
  white-space: nowrap;
}
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover .${ORIG_CLASS} { visibility: hidden; }
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover .${IPA_CLASS} {
  visibility: inherit;
  z-index: 1;
  background: inherit;
  box-shadow: 0 0 0 2px rgba(109,159,255,.12);
}

/* showOriginalOnHover: the IPA is the running text; the original overlays it. */
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} .${IPA_CLASS} { display: inline; visibility: inherit; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} .${ORIG_CLASS} {
  display: inline;
  visibility: hidden;
  position: absolute;
  left: 0;
  top: 0;
  white-space: nowrap;
}
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover .${IPA_CLASS} { visibility: hidden; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover .${ORIG_CLASS} {
  visibility: inherit;
  z-index: 1;
  background: inherit;
  box-shadow: 0 0 0 2px rgba(109,159,255,.12);
}
`.trim();

/** CSS for the tooltip (injected into Shadow DOM) */
export const TOOLTIP_CSS = `
* { box-sizing: border-box; margin: 0; padding: 0; }

.px-tt {
  position: fixed;
  display: none;
  width: max-content;
  max-width: 460px;
  min-width: 240px;
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
}
.px-lang {
  font-size: 9px;
  font-weight: 500;
  color: #888;
  text-transform: uppercase;
  letter-spacing: .5px;
}
.px-src {
  font-size: 9px;
  font-weight: 500;
  padding: 1px 5px;
  border-radius: 999px;
  text-transform: lowercase;
  letter-spacing: .3px;
}
.px-src-dict { color: #7fd7a8; background: rgba(93,232,176,.12); }
.px-src-espeak { color: #e0b978; background: rgba(224,185,120,.12); }
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
.px-sym {
  font: 500 24px/1.25 'Gentium Plus', 'Doulos SIL', 'Charis SIL', 'Noto Sans', serif;
  color: #eaeaf0;
  padding: 2px 2px 3px;
  border-radius: 4px;
  border-bottom: 2px solid transparent;
  cursor: default;
  transition: background .08s, color .08s;
}
.px-sym:hover { background: rgba(255,255,255,.10); }
.px-sym.clickable { cursor: pointer; }
.px-sym.clickable:active { transform: scale(.94); }
.px-sym.C { border-bottom-color: #6d9fff; }
.px-sym.V { border-bottom-color: #ff6d8f; }
.px-sym.S { border-bottom-color: #b78dff; }
.px-sym.D { border-bottom-color: #5de8b0; }

/* ── Detail line: fixed height, so exploring never resizes the tooltip ── */
.px-detail {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 6px 8px;
  border-radius: 6px;
  background: #232327;
}
.px-detail-empty { font-size: 11px; color: #6a6a72; }
.px-detail-sym {
  font: 500 16px/1 'Gentium Plus', 'Doulos SIL', 'Charis SIL', 'Noto Sans', serif;
  color: #6d9fff;
  flex: none;
}
.px-detail-text { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.px-detail-name { font-size: 11px; color: #d8d8de; font-weight: 500; }
.px-detail-eg { font-size: 11px; color: #7a7a82; }
.px-detail-spk { display: flex; margin-left: auto; color: #6d9fff; flex: none; }
.px-detail-spk svg { width: 12px; height: 12px; display: block; }
`.trim();
