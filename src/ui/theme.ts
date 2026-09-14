// Which palette every surface is drawn in.
//
// The tokens are defined per theme and mode, so a surface has to name one; naming it in each
// place a surface is drawn is how a card ends up in one palette and the annotations over the
// words in another. It is named here, once, and it is whichever the reader chose - the
// product's own until they pick another.

/** The product's own palette, and what a surface uses when nothing has been chosen. */
export const THEME = 'phonetix';

/** Every palette the tokens carry, in the order the settings offer them. */
export const THEMES = [
  'phonetix',
  'paper',
  'ink',
  'classroom',
  'nord',
  'dracula',
  'silk',
  'winter',
] as const;

export type Theme = (typeof THEMES)[number];

/** The two classes a surface needs: which palette, and which way round it is. */
export function themeOf(dark: boolean, theme: string = THEME): string {
  const named = (THEMES as readonly string[]).includes(theme) ? theme : THEME;
  return `theme-${named} mode-${dark ? 'dark' : 'light'}`;
}
