// Props a mounted card reads reactively, so a card filled in as more is found out about its
// word is the same card with new props rather than a new card: one remounted lost whatever the
// reader had open on it, the sound they asked about, a term's sheet, a form, an entry.
export function reactive<T extends object>(initial: T): T {
  const props = $state(initial);
  return props;
}
