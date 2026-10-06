// The settings view, where the browser puts it. Everything it does is in src/ext/popup.
import { mount } from 'svelte';
import App from '@/ext/popup/App.svelte';
import './app.css';
import { themeOf } from '@/ui/theme';
import { current, darkSide } from '@/settings';

// The palette on the document itself. The tokens are defined per theme and mode, so a
// stylesheet binding a component library to them has to find them at the root: put them on an
// element inside the page instead and :root carries none of them, the library falls back to
// its own colours, and the settings view comes out in a palette this product does not use.
const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
// The palette the reader chose and the side of it they asked for, read before the view is
// drawn so it opens in theirs rather than flickering out of the product's own.
void current().then((settings) => {
  document.documentElement.className = themeOf(darkSide(settings, dark), settings.theme);
});
document.documentElement.classList.add(...themeOf(dark).split(' '));

const app = document.getElementById('app')!;

// A whole number of pixels tall. The browser sizes the popup window to the document and rounds
// a fractional height down, and rows set in a line height of 1.3 are fractions of a pixel: a
// view 506.8px tall got a 506px window and scrolled by the pixel it lost.
// The body is held to the view's height rounded up, and the view itself is left free, so it is
// still told when a screen half as tall replaces it.
new ResizeObserver(() => {
  document.body.style.minHeight = `${Math.ceil(app.getBoundingClientRect().height)}px`;
}).observe(app);

export default mount(App, { target: app });
