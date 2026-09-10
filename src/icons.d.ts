// The icon sets, compiled to Svelte components by unplugin-icons. Declared so that a check of
// the types knows what `virtual:icons/...` is; the plugin is what actually resolves it.
declare module 'virtual:icons/*' {
  import type { Component } from 'svelte';
  const component: Component<{ class?: string }>;
  export default component;
}
