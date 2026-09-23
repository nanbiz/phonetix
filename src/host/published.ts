// Where the dictionaries and the list of translation models are published.
//
// A pack is built once from a dump of gigabytes and does not change again, which is what a
// release asset is for. A host of the reader's own is asked first where one is set, and what
// it does not have comes from here: a host set up before the packs were published serves an
// older set, or has gone, and a browser that asked it alone went on without either.
export const PUBLISHED = 'https://github.com/nanbiz/phonetix/releases/download/packs-v1';
