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
    ...(browser === 'firefox'
      ? { browser_specific_settings: { gecko: { id: 'phonetix@nanbiz.github.io' } } }
      : {}),
  }),
  ...(paths
    ? { webExt: { binaries: { chrome: paths['chrome'], firefox: paths['firefox'] } } }
    : {}),
});
