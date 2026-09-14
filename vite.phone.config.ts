// The phone's settings screen, built as a page the app hosts in a WebView.
//
// The same components the extension's popup is built from, bundled for a file:// document
// inside the app rather than for an extension page. Nothing about the screen is written twice:
// what differs is only where the answers come from, which is src/phone/bridge.ts.
import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import Icons from 'unplugin-icons/vite';
import path from 'path';

export default defineConfig({
  root: path.resolve(__dirname, 'src/phone'),
  // Loaded from file:///android_asset, which has no origin to resolve an absolute path
  // against: every reference has to be relative to the document.
  base: './',
  // The marks the extension ships, rather than a second copy of them beside this entry: the
  // product has one icon, in public/icon, and both surfaces are drawn with it.
  publicDir: path.resolve(__dirname, 'public/icon'),
  plugins: [svelte(), Icons({ autoInstall: true, compiler: 'svelte' })],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  build: {
    outDir: path.resolve(__dirname, 'android/app/src/main/assets/ui'),
    emptyOutDir: true,
    rollupOptions: {
      // Named for what they are rather than for their contents. A hash in the name is how a
      // browser is told a file has changed, and this page is not fetched over a network: it
      // is read out of the app. What the hash bought instead was a page whose cached markup
      // asked for a stylesheet the new build no longer had - and a screen with no palette,
      // no controls and a sheet with no background over all of it.
      output: {
        entryFileNames: 'settings.js',
        chunkFileNames: 'settings-[name].js',
        assetFileNames: 'settings.[ext]',
      },
    },
    // Readable, like the extension's build: what ships in an app is what can be read back
    // out of it, and the bundle is nothing next to a dictionary.
    minify: false,
    target: 'es2022',
  },
});
