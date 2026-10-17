import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import globals from 'globals';

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/coverage/**',
      '**/target/**',
      '**/*.log',
      '**/package-lock.json',
      'sdk-web/dist/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    // T23 E2E 支撑脚本为 Node 运行时（.mjs，js.configs 缺省浏览器全局会误报 no-undef）
    files: ['tests/e2e/**/*.mjs'],
    languageOptions: { globals: globals.node },
  },
);
