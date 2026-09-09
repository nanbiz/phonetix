// The card, in every state it has, drawn by the code that draws it for a reader.
//
// A page nobody links to: it exists so that what the card does can be looked at and measured
// without a dictionary, a page in Spanish or a reader's hover. It is the browser's twin of
// the phone's debug surface, and the checks read it.
import { mount } from 'svelte';
import Viewbook from './Viewbook.svelte';

export default mount(Viewbook, { target: document.getElementById('app')! });
