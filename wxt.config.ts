import { defineConfig } from 'wxt';
import tailwindcss from '@tailwindcss/vite'
import Icons from 'unplugin-icons/vite'
import { svelte } from '@sveltejs/vite-plugin-svelte';


export default defineConfig({
  vite: () => ({
    plugins: [
      //svelte(),
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
  },
  
});

