import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// T21 POC 独立可跑（npm test -w poc-micro-app-capture）；根 vitest.config.ts 亦以 project 聚合。
// alias 与根聚合口径一致（见根 vitest.config.ts 注释）：
// - vue/@vue/test-utils → 本工程 node_modules（vue@3.5.44 与根 vue@2.7.16 冲突时 npm 嵌套安装）
// - @nl-observe/sdk-web → SDK 源码（dist 未构建；与 esbuild bundle 的 alias 同一目标）
const here = (p: string): string => fileURLToPath(new URL(p, import.meta.url));

export default defineConfig({
  resolve: {
    alias: [
      { find: /^vue$/, replacement: here('./node_modules/vue') },
      { find: /^@vue\/test-utils$/, replacement: here('./node_modules/@vue/test-utils') },
      { find: /^@nl-observe\/sdk-web$/, replacement: here('../../sdk-web/src/index.ts') },
    ],
  },
  test: {
    // 根聚合经目录引用本文件；显式命名保证 --project 过滤稳定
    name: 'poc-micro-app-capture',
    environment: 'jsdom',
    include: ['test/**/*.spec.ts'],
    testTimeout: 60_000,
    // rc.32 探针（capture-rc32.spec.ts）会触发 micro-app 内部 unhandled rejection
    // （CustomWindow.dispatchEvent 被 jsdom 品牌校验拒绝——即被记录的结论本身），显式容忍
    dangerouslyIgnoreUnhandledErrors: true,
  },
});
