// The small marks the card uses in place of words.
//
// From the sets they were drawn for rather than cut here: Wiktionary's own book mark, and
// the speaker and microphone of a current icon set. Each one is a single path so it can be
// dropped into a shadow root without a second stylesheet, and each takes its colour from the
// button it sits in.
//
// What is ours is which mark means what: a voice a machine made and a voice a person recorded
// are different things, and the difference is drawn rather than spelled out.

/** Wiktionary, whose entry the word links to. */
export const WIKTIONARY = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path fill="currentColor" d="M2.22 18.6v.01c-.35-.21-.61-.5-.71-.84l-.07-.37L.21 3.36c-.03-.45.17-.9.57-1.25c.39-.36.97-.6 1.62-.66L15.35.22a2.8 2.8 0 0 1 1.7.35a1.5 1.5 0 0 1 .77 1.13l1.23 14.12c.03.45-.17.9-.57 1.25a2.85 2.85 0 0 1-1.62.67L3.92 18.95a2.75 2.75 0 0 1-1.7-.35m-1-1.1c.02.18.07.35.15.5l.02.25c.05.56.4 1.03.9 1.34c.51.3 1.19.46 1.9.4l13.34-1.27a3.15 3.15 0 0 0 1.8-.74c.45-.4.71-.93.66-1.49L18.73 1.87a1.77 1.77 0 0 0-.9-1.33a2.9 2.9 0 0 0-1.24-.4a3.2 3.2 0 0 0-1.27-.12L2.4 1.23a3.1 3.1 0 0 0-1.74.72c-.44.39-.7.9-.64 1.44l1.22 14.1zm1.2 1.9a1.6 1.6 0 0 1-.78-1a2 2 0 0 0 .47.39c.49.3 1.14.44 1.84.38l12.93-1.22c.7-.06 1.31-.33 1.74-.72c.44-.38.7-.9.64-1.43L18.04 1.69a1.62 1.62 0 0 0-.62-1.11c.1.04.2.09.29.15c.46.28.76.7.8 1.16l1.26 14.62c.04.48-.18.94-.59 1.3c-.4.37-1 .63-1.67.7L4.17 19.75a2.9 2.9 0 0 1-1.76-.36ZM1.21 5.3l4.34-.5l.06.47l-.28.04c-.28.03-.48.12-.6.26a.57.57 0 0 0-.15.46a12 12 0 0 0 .53 1.33l2.91 6.12l1.15-5.56l-.8-1.68c-.16-.27-.31-.5-.48-.7a1 1 0 0 0-.28-.23a1.4 1.4 0 0 0-.42-.17c-.1-.02-.25-.02-.48 0l-.08.02l-.06-.48l4.56-.53l.06.48l-.38.04c-.3.04-.5.13-.6.26a.67.67 0 0 0-.13.53c0 .02 0 .06.03.15l.15.4l3.2 6.84l1.32-6.48c.16-.75.22-1.25.18-1.51a.57.57 0 0 0-.14-.32a.57.57 0 0 0-.3-.18c-.2-.05-.47-.06-.8-.02h-.08l-.06-.48l3.53-.4l.06.48h-.08c-.29.04-.5.12-.66.24c-.15.12-.3.33-.42.64c-.08.2-.2.7-.35 1.5l-2.01 9.9l-.45.05l-3.4-7.06l-1.61 7.64l-.42.04l-4.56-9.42q-.51-1.05-.63-1.23a1 1 0 0 0-.47-.4a1.6 1.6 0 0 0-.76-.07h-.08Z"/></svg>';

/** A person saying it: the recording a reader trusts most. */
export const RECORDING = '<svg viewBox="0 0 24 24"><path fill="currentColor" d="M9 5a4 4 0 0 1 4 4a4 4 0 0 1-4 4a4 4 0 0 1-4-4a4 4 0 0 1 4-4m0 10c2.67 0 8 1.34 8 4v2H1v-2c0-2.66 5.33-4 8-4m7.76-9.64c2.02 2.2 2.02 5.25 0 7.27l-1.68-1.69c.84-1.18.84-2.71 0-3.89zM20.07 2c3.93 4.05 3.9 10.11 0 14l-1.63-1.63c2.77-3.18 2.77-7.72 0-10.74z"/></svg>';

/** A sound being played, which is what the detail line offers for one symbol. */
export const SPEAKER = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/></svg>';

/** Read about this sound, on the article the symbol table points at. */
export const ARTICLE =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
  'stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/>' +
  '<path d="M12 16v-4"/><path d="M12 8h.01"/></svg>';

/** Films of a real mouth saying it, which is what Seeing Speech has. */
export const FILM =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
  'stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="20" height="16" ' +
  'rx="2"/><path d="M7 4v16M17 4v16M2 12h20M2 8h5M2 16h5M17 8h5M17 16h5"/></svg>';
