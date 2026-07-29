import { defineConfig } from 'wxt';
import tailwindcss from '@tailwindcss/vite';
import Icons from 'unplugin-icons/vite';
import fs from 'fs';
import path from 'path';

// Browsers for `wxt dev` are downloaded by scripts/install-browsers.mjs. They are
// absent on a clean checkout and in CI, where only a build is needed.
function devBrowsers(): Record<string, string> | undefined {
  try {
    return JSON.parse(
      fs.readFileSync(path.join(process.cwd(), '.cache', 'browsers', 'paths.json'), 'utf-8')
    );
  } catch {
    return undefined;
  }
}
const paths = devBrowsers();

export default defineConfig({
  vite: () => ({
    // Ship readable, unminified code. AMO flags minified/bundled code for manual review
    // and asks a reviewer to diff the packaged output against the submitted source; an
    // unminified build makes that diff trivial, which is what keeps an unlisted add-on
    // from stalling in review for weeks. The bundle is tiny next to the espeak data, so
    // there is no meaningful size cost.
    build: { minify: false },
    plugins: [
      tailwindcss(),
      Icons({
        autoInstall: true,
        compiler: 'svelte',
      }),
    ],
  }),
  srcDir: 'src',
  modules: ['@wxt-dev/module-svelte'],
  // offscreen is a Chromium-only API; Firefox runs espeak in its background page.
  manifest: ({ browser }) => ({
    name: 'Phonetix - Learn and Understand IPA',
    // The toolbar-button tooltip, on both engines (WXT maps action → browser_action
    // on Firefox MV2). Without this it shipped as the literal "Default Popup Title".
    action: { default_title: 'Phonetix - Learn and Understand IPA' },
    permissions: browser === 'firefox' ? ['storage'] : ['storage', 'offscreen'],
    host_permissions: ['<all_urls>'],
    // The content script reads the common-word list (sprinkle mode) straight from the
    // extension, so it has to be reachable from the page's own context.
    web_accessible_resources: [
      { resources: ['common-words.json'], matches: ['<all_urls>'] },
    ],
    content_security_policy: {
      extension_pages:
        "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'",
    },
    // Floors the store review + install can rely on. Firefox: Intl.Segmenter (used to
    // split words) landed in 125. Chrome: the offscreen document (used to run espeak)
    // needs 109.
    ...(browser === 'firefox'
      ? { browser_specific_settings: { gecko: { id: 'phonetix@nanbiz.github.io', strict_min_version: '125.0' } } }
      : { minimum_chrome_version: '109' }),
  }),
  ...(paths
    ? { webExt: { binaries: { chrome: paths['chrome'], firefox: paths['firefox'] } } }
    : {}),
});
