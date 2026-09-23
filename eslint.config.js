// The extension's lint.
//
// Type-aware, because the mistakes that have cost this product the most are ones only types
// can see: a promise nobody awaited, so a failure vanished and a feature was quietly off; a
// value that was sometimes undefined read as though it never was. svelte-check proves the
// types agree; this is about what the code does with them.
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import svelte from 'eslint-plugin-svelte';
import globals from 'globals';

export default tseslint.config(
  {
    ignores: [
      '.output/**',
      '.wxt/**',
      'node_modules/**',
      'public/**',
      'android/**',
      'core/**',
      'dev/**',
      'src/data/wording.ts',
      'src/data/languages.ts',
      // Written by wasm-bindgen from the core.
      'src/core/wasm/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  ...svelte.configs['flat/recommended'],
  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.webextensions, browser: 'readonly' },
      parserOptions: {
        projectService: true,
        extraFileExtensions: ['.svelte'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ['**/*.svelte', '**/*.svelte.ts'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    rules: {
      // What matters most: a promise left floating is an error nobody hears.
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',
      // The engines and the browser's own APIs are untyped at their edges; `any` there is
      // the boundary, not a lapse.
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      '@typescript-eslint/no-unsafe-return': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      // A space written as {" "} is a space that survives the template's own whitespace
      // rules, which is the only way to put one between two inline elements in a loop.
      'svelte/no-useless-mustaches': 'off',
    },
  },
  {
    // The two controls that draw a mark: what they insert is the icon set's own SVG, bundled
    // with the product and passed in by our own components, never text from a page.
    files: ['src/ui/controls/IconButton.svelte', 'src/ui/controls/IconLink.svelte'],
    rules: { 'svelte/no-at-html-tags': 'off' },
  },
);
