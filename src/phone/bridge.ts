// What the phone's settings view can ask the app it is drawn inside.
//
// The view is the product's own, the same component the extension's popup draws. What differs
// is where the answers come from: a browser has storage and a service worker, and a phone has
// Kotlin on the other side of a WebView. Everything that crosses that boundary is here, so the
// boundary is one file rather than a habit.
//
// One channel, asked and answered by number: a WebView's Java interface is synchronous and
// runs on the JavaScript thread, so a question that opens a seventeen megabyte model would
// freeze the screen it was asked from. The app answers whenever it is done, through the
// callback below.

/** The object the app injects. Absent when the view is opened anywhere else. */
interface Native {
  ask(id: number, kind: string, payload: string): void;
}

declare global {
  interface Window {
    Phonetix?: Native;
    /** Called by the app with the answer to one question. */
    phonetixAnswer?: (id: number, ok: boolean, json: string) => void;
    /** Called by the app when something changed that this view did not change itself: a
     *  permission granted in the system's settings, an app chosen on the app's own screen. */
    phonetixChanged?: () => void;
    /** Called by the app to open one of this view's screens, where something outside asked
     *  for it: holding the mark asks for the word a reader is looking for. Answers whether
     *  the view was ready to open it. */
    phonetixOpen?: (view: string) => boolean;
    /** Called by the app when the reader uses the device's own way back. Answers whether
     *  there was a screen to leave; when there is not, the app closes as it always would. */
    phonetixBack?: () => boolean;
  }
}

let next = 1;
const waiting = new Map<number, { keep: (value: unknown) => void; give: (e: Error) => void }>();

if (typeof window !== 'undefined') {
  window.phonetixAnswer = (id, ok, json) => {
    const asked = waiting.get(id);
    if (!asked) return;
    waiting.delete(id);
    let value: unknown = null;
    try {
      value = json ? JSON.parse(json) : null;
    } catch (e) {
      asked.give(new Error(`the app answered with ${json}: ${e}`));
      return;
    }
    if (ok) asked.keep(value);
    else asked.give(new Error(String(value)));
  };
}

/** Whether this view is drawn inside the app at all. */
export function onPhone(): boolean {
  return typeof window !== 'undefined' && Boolean(window.Phonetix);
}

/** Ask the app something, and wait for it to answer. */
export function ask<T>(kind: string, payload: unknown = {}): Promise<T> {
  return new Promise<T>((keep, give) => {
    const native = window.Phonetix;
    if (!native) {
      give(new Error('there is no app on the other side of this'));
      return;
    }
    const id = next++;
    waiting.set(id, { keep: keep as (value: unknown) => void, give });
    try {
      native.ask(id, kind, JSON.stringify(payload));
    } catch (e) {
      waiting.delete(id);
      give(e instanceof Error ? e : new Error(String(e)));
    }
  });
}

/** Be told when the app changed something this view did not. */
export function whenChanged(told: () => void): void {
  window.phonetixChanged = told;
}
