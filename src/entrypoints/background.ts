// The extension's long-lived side, which is the host and nothing else.
//
// Everything it does is in src/host: it owns the core, the packs the reader has, and the
// answers a page asks for. This file exists because the browser needs an entry point.
import { host } from '@/host';

export default defineBackground(() => {
  host();
});
