// Which palette every surface is drawn in.
//
// The tokens are defined per theme and mode, so a surface has to name one; naming it in each
// place a surface is drawn is how a card ends up in one palette and the annotations over the
// words in another. It is named here, once, and it is the product's own - the icon's colours,
// declared on the surface page as the "phonetix" theme and generated into both platforms.

/** The palette this product draws itself in. */
export const THEME = 'theme-phonetix';

/** The two classes a surface needs: which palette, and which way round it is. */
export function themeOf(dark: boolean): string {
  return `${THEME} mode-${dark ? 'dark' : 'light'}`;
}
