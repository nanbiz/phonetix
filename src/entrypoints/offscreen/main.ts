// A page that exists only to hold the voice.
//
// The engine is a WebAssembly module that is loaded from a URL and wants a document; a
// Chromium service worker has neither, and cannot import a module at all. So it lives here,
// in the smallest page an extension can have, and the host asks it through a message.
//
// Firefox needs none of this: its background page has a document, and the host runs the same
// engine directly there.
import { phonemizeBatch, synthesizeWav } from '@/engines/espeak';
import { pairs, translate } from '@/engines/bergamot';

interface Asked {
  voice: 'ipa' | 'audio' | 'translate' | 'pairs';
  lang: string;
  /** Where a translation is going, which the voice engines have no use for. */
  into?: string;
  /** Where the models are served from. Passed in: this page has no access to the setting. */
  base?: string;
  words: string[];
}

chrome.runtime.onMessage.addListener((message: unknown, _sender, respond) => {
  const asked = message as Asked;
  if (!asked || typeof asked !== 'object' || !('voice' in asked)) return false;
  if (asked.voice === 'ipa') {
    phonemizeBatch(asked.words, asked.lang)
      .then((said) => respond({ ok: said }))
      .catch((e) => respond({ failed: String(e) }));
    return true;
  }
  if (asked.voice === 'translate') {
    translate(asked.base ?? '', asked.lang, asked.into ?? '', asked.words)
      .then((said) => respond({ ok: said }))
      .catch((e) => respond({ failed: String(e) }));
    return true;
  }
  if (asked.voice === 'pairs') {
    pairs(asked.base ?? '')
      .then((got) => respond({ ok: got }))
      .catch((e) => respond({ failed: String(e) }));
    return true;
  }
  if (asked.voice === 'audio') {
    synthesizeWav(asked.words[0] ?? '', asked.lang)
      .then((wav) => respond({ ok: Array.from(wav) }))
      .catch((e) => respond({ failed: String(e) }));
    return true;
  }
  return false;
});
