// The settings view, where the browser puts it. Everything it does is in src/ext/popup.
import { mount } from 'svelte';
import App from '@/ext/popup/App.svelte';

export default mount(App, { target: document.getElementById('app')! });
