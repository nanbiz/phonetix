// The extension's side of a page.
//
// Everything it does is in src/ext/content: scanning the document into runs, drawing what the
// core decided over the words, and opening a card at the word a reader stopped on. This file
// exists because the browser needs an entry point.
import { session } from '@/ext/content';

export default defineContentScript({
  matches: ['<all_urls>'],
  runAt: 'document_idle',
  main() {
    void session();
  },
});
