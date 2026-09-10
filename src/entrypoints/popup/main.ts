// The settings view, where the browser puts it. Everything it does is in src/ext/popup.
import { mount } from 'svelte';
import App from '@/ext/popup/App.svelte';
import './app.css';

// The palette on the document itself. The tokens are defined per theme and mode, so a
// stylesheet binding a component library to them has to find them at the root: put them on an
// element inside the page instead and :root carries none of them, the library falls back to
// its own colours, and the settings view comes out in a palette this product does not use.
const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
document.documentElement.classList.add('theme-paper', dark ? 'mode-dark' : 'mode-light');

export default mount(App, { target: document.getElementById('app')! });
