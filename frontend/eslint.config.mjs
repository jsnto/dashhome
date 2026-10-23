// ESLint flat config for the dashhome frontend.
//
// Conservative gate: the standard recommended rules for JS + TypeScript
// (typescript-eslint) plus the two classic react-hooks rules.
// eslint-config-prettier turns off every stylistic rule that prettier
// owns, so the two tools never fight.
//
// Grandfathering: none needed - at gate landing the existing tree was
// clean under this set (two `prefer-const` findings were fixed in the
// same change).
import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import prettierConfig from 'eslint-config-prettier'

export default tseslint.config(
  { ignores: ['dist/'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    plugins: { 'react-hooks': reactHooks },
    rules: {
      // The two classic rules. The plugin's newer "compiler-era" rules
      // (immutability, refs, purity, ...) are experimental and are not
      // enabled. exhaustive-deps stays a warning (never blocks the gate),
      // matching the existing `eslint-disable-line` conventions in the
      // codebase.
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
  prettierConfig,
)
