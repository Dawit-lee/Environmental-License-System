import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

// minimal on purpose: hook rules catch stale deps and conditional hooks; tsc covers types
export default tseslint.config(
  { ignores: ['dist'] },
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: { parser: tseslint.parser },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // warn, not error: 12 effects intentionally re-run only on an id (load-on-change); rules-of-hooks still fails CI
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
);
