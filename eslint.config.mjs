import js from '@eslint/js';
import tseslint from 'typescript-eslint';

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
);
