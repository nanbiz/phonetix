import { defineConfig } from 'wxt';
import tailwindcss from '@tailwindcss/vite'
import Icons from 'unplugin-icons/vite'
import fs from 'fs'
import path from 'path';
const paths = JSON.parse(fs.readFileSync(path.join(process.cwd(), ".cache", "browsers", "paths.json"), "utf-8"))
export default defineConfig({
  vite: () => ({
    plugins: [
      tailwindcss(),
      Icons({ 
        autoInstall: true,
        // compiler: 'raw',
        compiler: 'svelte'
      }),
    ],
  }),
  srcDir: 'src',
  modules: ['@wxt-dev/module-svelte'],
  manifest: {
    permissions: ['storage'],
    host_permissions: ['<all_urls>']
  },
  webExt: {
    binaries: {
      chrome: paths["chrome"],
      firefox: paths["firefox"]
    }
  }
});

