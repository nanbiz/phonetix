// The phone's settings screen, mounted in the WebView the app hosts.
import { mount } from 'svelte';
import App from './App.svelte';
import './app.css';
import { themeOf } from '@/ui/theme';

// Something to look at before the app has answered what the reader chose: the product's own
// palette, replaced by theirs the moment it arrives.
document.documentElement.className = themeOf(
  window.matchMedia('(prefers-color-scheme: dark)').matches
);

export default mount(App, { target: document.getElementById('app')! });
