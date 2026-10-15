import { defineConfig } from 'tsup';

// 详设 §1.5：双入口构建（tsup：es + cjs 双格式）。core 共享，vue2/vue3 入口仅薄适配层。
export default defineConfig({
  entry: {
    index: 'src/index.ts',
    vue2: 'src/vue2.ts',
    vue3: 'src/vue3.ts',
  },
  format: ['esm', 'cjs'],
  dts: true,
  target: 'es2020',
  clean: true,
  sourcemap: true,
});
