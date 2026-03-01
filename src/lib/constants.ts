export const BLOCKED_TAGS = new Set([
  'SCRIPT', 'STYLE', 'NOSCRIPT', 'TEXTAREA', 'INPUT', 'SELECT',
  'CODE', 'PRE', 'KBD', 'SAMP', 'VAR', 'SVG', 'MATH', 'CANVAS',
  'VIDEO', 'AUDIO', 'IFRAME', 'OBJECT', 'EMBED',
]);

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
 * Hover modes: use inline-grid to stack both spans in the same cell.
 * The cell is always as wide as the wider span, preventing reflow
 * twitching when the IPA and original text have different widths.
 */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS},
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} {
  display: inline-grid;
  vertical-align: baseline;
}
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} > *,
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} > * {
  grid-area: 1 / 1;
}

/* onHover: show original, IPA hidden but reserving space */
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} .${ORIG_CLASS} { visibility: visible; }
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS} .${IPA_CLASS} { display: inline; visibility: hidden; }
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover .${ORIG_CLASS} { visibility: hidden; }
.${MODE_CLASSES.onHover} .${PHONETIX_CLASS}:hover .${IPA_CLASS} { visibility: visible; }

/* showOriginalOnHover: show IPA, original hidden but reserving space */
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} .${ORIG_CLASS} { display: inline; visibility: hidden; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS} .${IPA_CLASS} { display: inline; visibility: visible; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover .${IPA_CLASS} { visibility: hidden; }
.${MODE_CLASSES.showOriginalOnHover} .${PHONETIX_CLASS}:hover .${ORIG_CLASS} { visibility: visible; }
`.trim();

/** CSS for the tooltip (injected into Shadow DOM) */
export const TOOLTIP_CSS = `
* { box-sizing: border-box; margin: 0; padding: 0; }

.px-tt {
  position: fixed;
  display: none;
  width: max-content;
  max-width: 420px;
  padding: 10px 12px;
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
  gap: 6px;
  margin-bottom: 4px;
}
.px-word {
  font-size: 15px;
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

/* ── Row 2: IPA ── */
.px-r2 {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
}
.px-ipa-text {
  font: 14px/1 'Gentium Plus', 'Doulos SIL', 'Charis SIL', 'Noto Sans', serif;
  color: #6d9fff;
}

/* ── Symbol grid ── */
.px-symbols {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  margin-bottom: 2px;
}
.px-sym {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 28px;
  padding: 0 4px;
  background: #252528;
  border-radius: 4px;
  border-bottom: 2px solid transparent;
  cursor: default;
  transition: background .08s;
  position: relative;
}
.px-sym:hover { background: #303035; }
.px-sym.clickable { cursor: pointer; }
.px-sym.clickable:active { transform: scale(.93); }
.px-sym.C { border-bottom-color: #6d9fff; }
.px-sym.V { border-bottom-color: #ff6d8f; }
.px-sym.S { border-bottom-color: #b78dff; }
.px-sym.D { border-bottom-color: #5de8b0; }
.px-sym-ch {
  font: 500 14px/1 'Gentium Plus', 'Doulos SIL', 'Charis SIL', 'Noto Sans', serif;
  color: #e0e0e4;
}
.px-sym .px-sym-spk {
  display: none;
  position: absolute;
  top: 0; right: 0;
  width: 8px; height: 8px;
  color: #6d9fff;
}
.px-sym.clickable:hover .px-sym-spk { display: block; }

/* ── Detail line ── */
.px-detail {
  height: 36px;
  overflow: hidden;
  padding: 4px 6px;
  border-radius: 4px;
  background: #222225;
  margin-bottom: 4px;
}
.px-detail-name { font-size: 10px; color: #aaa; font-weight: 500; }
.px-detail-eg { font-size: 10px; color: #666; }
.px-detail-empty { font-size: 10px; color: #555; font-style: italic; }

/* ── Footer: legend + wiktionary ── */
.px-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 4px;
  border-top: 1px solid #2a2a2d;
}
.px-leg {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 9px;
  color: #666;
}
.px-dot {
  width: 5px; height: 5px;
  border-radius: 50%;
}
.px-dot.C { background: #6d9fff; }
.px-dot.V { background: #ff6d8f; }
.px-dot.S { background: #b78dff; }
`.trim();
