import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'

export default tseslint.config(
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser
      }
    },
    rules: {
      // .vue 由 tseslint.parser 解析、vue-tsc 类型检查兜底未定义标识符（typescript-eslint 官方口径：
      // TS 语境下 no-undef 应关闭，否则 window/KeyboardEvent 等浏览器全局误报）
      'no-undef': 'off'
    }
  },
  {
    rules: {
      'vue/multi-word-component-names': 'off'
    }
  }
)
