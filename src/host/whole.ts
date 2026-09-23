// A large file, fetched whole, by a background that may be put to sleep while it arrives.
//
// Chrome documents that it ends an extension's service worker after thirty seconds in which it
// neither received an event nor called an extension API, and a response body streaming in is
// neither (developer.chrome.com, "The extension service worker lifecycle"). Firefox's event
// page idles out on the same kind of rule. A dictionary is tens of megabytes, which on a slow
// line is minutes. Reading the body a piece at a time and making one cheap extension call every
// few seconds while it arrives keeps the worker awake for as long as there is something
// arriving, and for no longer.

/** How often the worker is reminded it is busy, well inside the thirty seconds. */
const REMIND_MS = 10_000;

/**
 * One call that costs nothing and counts as activity. Absent in a document that has only part
 * of the runtime - an offscreen document, which is not put to sleep this way anyway.
 */
function remind(): void {
  try {
    const runtime = (globalThis as { chrome?: { runtime?: { getPlatformInfo?: () => unknown } } })
      .chrome?.runtime;
    const asked = runtime?.getPlatformInfo?.();
    // Answered or not, it has done its work by being asked.
    if (asked instanceof Promise) asked.catch(() => undefined);
  } catch {
    // Nothing to remind: the page this runs in is not one that sleeps.
  }
}

/** The whole body of a response, read so that the worker reading it stays awake. */
export async function whole(res: Response): Promise<Uint8Array<ArrayBuffer>> {
  remind();
  const awake = setInterval(remind, REMIND_MS);
  try {
    if (!res.body) return new Uint8Array(await res.arrayBuffer());
    const reader = res.body.getReader();
    const pieces: Uint8Array[] = [];
    let length = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      pieces.push(value);
      length += value.length;
    }
    const out = new Uint8Array(length);
    let at = 0;
    for (const piece of pieces) {
      out.set(piece, at);
      at += piece.length;
    }
    return out;
  } finally {
    clearInterval(awake);
  }
}
