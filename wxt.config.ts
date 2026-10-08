import { defineConfig } from 'wxt';
import Icons from 'unplugin-icons/vite';
import { execSync } from 'child_process';
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

/** The service worker's name in this build, between naming it and moving the file. */
let renamed: { from: string; to: string } | undefined;

export default defineConfig({
  vite: () => ({
    // Ship readable, unminified code. AMO flags minified/bundled code for manual review
    // and asks a reviewer to diff the packaged output against the submitted source; an
    // unminified build makes that diff trivial, which is what keeps an unlisted add-on
    // from stalling in review for weeks. The bundle is tiny next to the espeak data, so
    // there is no meaningful size cost.
    build: { minify: false },
    // Icon sets compiled to Svelte components, so a mark is a component from the set that
    // drew it rather than a path this repository cuts and has to keep legible itself. The
    // surfaces themselves are drawn in the generated tokens, which the phone is drawn in too.
    plugins: [Icons({ autoInstall: true, compiler: 'svelte' })],
  }),
  srcDir: 'src',
  // AMO wants the source of a build it signs, and the default sweep takes the repository with
  // it: the Rust build directory, the fetched dictionaries and the espeak data made an 850 MB
  // archive that took ten minutes to write. What a reviewer needs is the source.
  zip: {
    excludeSources: [
      'core/target/**',
      'assets/**',
      'public/espeak/**',
      'public/core/**',
      'android/**',
      'docs/**',
      'scripts/proofread/**',
      '**/*.pack',
    ],
  },
  modules: ['@wxt-dev/module-svelte'],
  hooks: {
    // Chromium can keep running the service worker it registered for an unpacked extension
    // for as long as the worker's file name stays the same, across restarts and new versions
    // alike, so a browser that loads this build with --load-extension may run an old worker
    // under new pages. Each build's worker is therefore named after its version, and the
    // version carries the commit count as a fourth part so that the name and the version
    // change together: a new name under a version the browser already registered is a
    // worker that never starts. version_name keeps the package's version for display and
    // for the zip's name. A build of uncommitted work has its commit's name and version and
    // is picked up once committed.
    'build:manifestGenerated': (wxt, manifest) => {
      if (wxt.config.browser === 'firefox' || wxt.config.command !== 'build') return;
      const background = manifest.background;
      if (!background || !('service_worker' in background)) return;
      const worker = background.service_worker;
      let commits = '0';
      try {
        commits = execSync('git rev-list --count HEAD', { encoding: 'utf-8' }).trim();
      } catch {
        // Outside a checkout the build keeps one name, which is all a fresh install needs.
      }
      manifest.version_name = manifest.version_name ?? manifest.version;
      manifest.version = `${manifest.version}.${commits}`;
      renamed = { from: worker, to: `background-${manifest.version}.js` };
      background.service_worker = renamed.to;
    },
    // The file follows its name once the build is done, and the build's own list of what it
    // wrote with it, which the summary after this reads from disk.
    'build:done': (wxt, output) => {
      if (!renamed) return;
      const { from, to } = renamed;
      renamed = undefined;
      fs.renameSync(path.join(wxt.config.outDir, from), path.join(wxt.config.outDir, to));
      for (const step of output.steps) {
        for (const chunk of step.chunks) {
          if (chunk.fileName === from) chunk.fileName = to;
        }
      }
    },
  },
  manifest: ({ browser }) => ({
    name: 'Phonetix - Learn and Understand IPA',
    // The voice needs a document and a Chromium service worker has none, so on Chromium it
    // lives in an offscreen page. Firefox's background page has one and needs no permission.
    // The toolbar button's tooltip, on both engines. Without it the settings view shipped as
    // the literal "Default Popup Title".
    action: { default_title: 'Phonetix' },
    // tabs, only to know which site the settings view is being opened over: a switch for
    // this site is not a switch at all if it cannot tell which site that is.
    // declarativeNetRequest, on Chromium only, for the one rule in public/rules: Mozilla's
    // server for the translation models refuses a request that names Chrome as its browser
    // (406) and serves the same file to Firefox, so the extension names Firefox to that
    // server alone. Firefox needs nothing.
    permissions: browser === 'firefox'
      ? ['storage', 'tabs']
      : ['storage', 'tabs', 'offscreen', 'declarativeNetRequest'],
    host_permissions: ['<all_urls>'],
    // The word a reader is looking for, without reaching for the mouse: the same question the
    // phone's mark answers, on the surface where a reader already has both hands on the
    // keyboard. Alt rather than Ctrl or Command, which every page and every site has already
    // taken.
    commands: {
      'ask-for-a-word': {
        suggested_key: { default: 'Alt+Shift+P' },
        description: 'Ask for the word for something',
      },
    },
    content_security_policy: {
      extension_pages:
        "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'",
    },
    // Floors the store review + install can rely on. Firefox is 140 because that is
    // where the built-in data-consent manifest below is honoured (it otherwise needs
    // 125 for Intl.Segmenter). Chrome 109 is where a service worker may instantiate
    // WebAssembly under the policy below, which is how the core runs at all.
    //
    // data_collection_permissions declares that the add-on transmits website content:
    // the word under the cursor is sent to Wiktionary/Wikimedia to fetch its IPA, audio,
    // and articulation diagram. Firefox shows this at install and lets the user see it in
    // about:addons, which is the consent Mozilla's policy requires. The inline
    // transcription itself is fully offline (bundled dictionaries + espeak); only the
    // hover tooltip's enrichment leaves the browser.
    ...(browser === 'firefox'
      ? {
          browser_specific_settings: {
            gecko: {
              id: 'phonetix@nanbiz.github.io',
              strict_min_version: '140.0',
              data_collection_permissions: { required: ['websiteContent'] },
            },
          },
        }
      : {
          minimum_chrome_version: '109',
          declarative_net_request: {
            rule_resources: [
              { id: 'mozilla-models', enabled: true, path: 'rules/mozilla-models.json' },
            ],
          },
          // The public half of the key the crx is signed with (scripts/release.sh), so an
          // unpacked build loads under the same id as the crx, cpdkbgaandlgpgifdneoffhclgedkghm,
          // and takes over its settings instead of arriving as a second, empty Phonetix.
          key: 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuhN4/UdvsfVt7F421/s/PzHmAdtue34oMVAgxiiDZGZgKJ0/y0PVItQ7iqR8IyohvZbP3OvoN5ffH1AF/ZSy0JwTfJbqIDLV74VjW+3uPkQjDFH3IDzhBlZZp92I8g4vj8E53OlgwZ1HzfDEYhi9X5UZfrE+UHjMp0ZuW2m6vCJaIGNoCBb6uz7ACUbwS/iSGVb3ebl5KYKf3N6jS4pCEgnb1fr1nKidmglEaNAt7xIjiAHs1hdDghH/cdMKw0ffCQuR3ICnAJoytFkTjCWaIjOaHq39AwWVJ7QXlG3lpDTMVvfMnNVRExEjQb08JNK55tgfw8aQc8vaqP6LoPNtUwIDAQAB',
        }),
  }),
  ...(paths
    ? { webExt: { binaries: { chrome: paths['chrome'], firefox: paths['firefox'] } } }
    : {}),
});
