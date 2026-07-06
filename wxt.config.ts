import { defineConfig } from 'wxt';
import tailwindcss from '@tailwindcss/vite';
import Icons from 'unplugin-icons/vite';
import fs from 'fs';
import path from 'path';

const paths = JSON.parse(
  fs.readFileSync(
    path.join(process.cwd(), '.cache', 'browsers', 'paths.json'),
    'utf-8'
  )
);

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
    content_security_policy: {
      extension_pages:
        "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'",
    },
    ...(browser === 'firefox'
      ? { browser_specific_settings: { gecko: { id: 'phonetix@extension' } } }
      : {}),
  }),
  webExt: {
    binaries: {
      chrome: paths['chrome'],
      firefox: paths['firefox'],
    },
  },
});
