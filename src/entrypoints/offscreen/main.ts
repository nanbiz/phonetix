// A page that exists only to hold the voice.
//
// The engine is a WebAssembly module that is loaded from a URL and wants a document; a
// Chromium service worker has neither, and cannot import a module at all. So it lives here,
// in the smallest page an extension can have, and the host asks it through a message.
//
// Firefox needs none of this: its background page has a document, and the host runs the same
// engine directly there.
//
// It is also where the translator panel's microphone records, since a page made with the
// reason to record may use a microphone the extension was allowed once, and the page being
// read may not.
import { phonemizeBatch, synthesizeWav } from '@/engines/espeak';
import { pairs, translate } from '@/engines/bergamot';
import { allowed, record, resample, type Recording } from '@/engines/listen';
import { prepare, transcribe, type Hosts } from '@/engines/whisper';

interface Asked {
  voice: 'ipa' | 'audio' | 'translate' | 'pairs' | 'listen' | 'stop-listening' | 'microphone';
  lang: string;
  /** Where a translation is going, which the voice engines have no use for. */
  into?: string;
  /** Where the models are served from. Passed in: this page has no access to the setting. */
  base?: string;
  /** And the speech model, which has two. */
  hosts?: Hosts;
  words: string[];
}

/** What is being recorded now, so the panel's second press can end it. */
let recording: Recording | null = null;

/** Tell the host how far along a question is: this page cannot reach the storage the panel
 *  watches, and the host can. */
function phase(listening: { phase: string; share?: number } | null): void {
  void chrome.runtime.sendMessage({ listening }).catch(() => undefined);
}

/** Record what the reader says and write it down, in [lang].
 *  The model is made ready while they speak, and the first time that is a download, whose
 *  progress goes with whatever else is happening. */
async function listen(hosts: Hosts, lang: string): Promise<string> {
  recording?.stop();
  // Asking until the microphone is sending, though the model's progress is told meanwhile.
  let now: 'asking' | 'listening' | 'thinking' = 'asking';
  let share: number | undefined;
  const tell = () => phase({ phase: now, ...(share !== undefined && share < 1 ? { share } : {}) });
  const ready = prepare(hosts, (got) => {
    share = got;
    tell();
  });
  ready.catch(() => undefined);
  const it = await record();
  recording = it;
  now = 'listening';
  tell();
  const { samples, rate, spoke } = await it.done;
  if (recording === it) recording = null;
  if (!spoke) return '';
  now = 'thinking';
  tell();
  await ready;
  return transcribe(hosts, resample(samples, rate), lang);
}

chrome.runtime.onMessage.addListener((message: unknown, _sender, respond) => {
  const asked = message as Asked;
  if (!asked || typeof asked !== 'object' || !('voice' in asked)) return false;
  if (asked.voice === 'microphone') {
    void allowed().then((state) => respond({ ok: state }));
    return true;
  }
  if (asked.voice === 'listen') {
    if (!asked.hosts) return false;
    listen(asked.hosts, asked.lang)
      .then((said) => respond({ ok: said }))
      .catch((e) => respond({ failed: String(e) }));
    return true;
  }
  if (asked.voice === 'stop-listening') {
    recording?.stop();
    respond({ ok: true });
    return false;
  }
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
