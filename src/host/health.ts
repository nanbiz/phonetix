// What has stopped answering, as it happens.
//
// An engine that quietly fails looks exactly like a word no dictionary holds: the annotation is
// simply missing, and a reader concludes the product is bad at the language they are reading.
// The one place they can find out is the settings view, and what it shows is this.
//
// Recorded rather than probed. Asking each engine whether it is alive means waking it, running
// a word through it and taking down whatever it had going - which is how a health check ends up
// being the thing that breaks the health it reports on. What is reported here is what actually
// failed while answering something a reader asked for.

/** One thing that went wrong, and when. */
interface Trouble {
  what: string;
  said: string;
  at: number;
}

/** How long a failure is worth reporting. Older than this and the reader has moved on. */
const REMEMBERED = 10 * 60 * 1000;

const seen = new Map<string, Trouble>();

/**
 * Something failed. Kept under its own name, so one engine failing repeatedly is one line
 * rather than a hundred.
 *
 * What is kept is the sentence a reader can act on, not the exception: "Failed to fetch" is
 * what the console is for, and a reader reading it learns nothing they can do. The caller says
 * what happened in words because the caller is the one that knows what it was trying to do.
 */
export function noted(what: string, said: string): void {
  seen.set(what, { what, said, at: Date.now() });
}

/** Something answered. Whatever it was blamed for before is over. */
export function answered(what: string): void {
  seen.delete(what);
}

/** What has failed recently, in the words a reader can act on. */
export function recent(): string[] {
  const now = Date.now();
  const out: string[] = [];
  for (const [name, trouble] of seen) {
    if (now - trouble.at > REMEMBERED) {
      seen.delete(name);
      continue;
    }
    out.push(`${trouble.what} ${trouble.said}`);
  }
  return out;
}
