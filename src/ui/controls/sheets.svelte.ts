// What is open over the screen, so the way back closes it.
//
// A phone's back gesture belongs to the app, and the app has to know whether there is anything
// to leave: a list opened over the settings is not a screen the system knows about, so going
// back from one closed the whole app. Every surface that opens over another says so here, and
// the way back closes the last one opened.

/** The things standing open, each knowing how to close itself, oldest first. */
const open = $state<(() => void)[]>([]);

/** Say that this is open until the returned function is called. */
export function standing(close: () => void): () => void {
  open.push(close);
  return () => {
    const at = open.indexOf(close);
    if (at >= 0) open.splice(at, 1);
  };
}

/** Whether anything is open over the screen. */
export function covered(): boolean {
  return open.length > 0;
}

/** Close the last thing opened. Answers whether there was one. */
export function uncover(): boolean {
  const close = open.pop();
  if (!close) return false;
  close();
  return true;
}
