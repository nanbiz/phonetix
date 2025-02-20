import { defineConfig } from 'wxt';
import tailwindcss from '@tailwindcss/vite'
import Icons from 'unplugin-icons/vite'


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
  extensionApi: 'chrome',
  modules: ['@wxt-dev/module-svelte'],
  manifest: {
    permissions: ['storage'],
    host_permissions: ['<all_urls>']
  },
  
});

