import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// T21 POC 独立可跑（npm test -w poc-micro-app-capture）；根 vitest.config.ts 亦以 project 聚合。
// alias 与根聚合口径一致（见根 vitest.config.ts 注释）：
// - vue/@vue/test-utils → 本工程 node_modules（vue@3.5.44 与根 vue@2.7.16 冲突时 npm 嵌套安装）
// - @nl-observe/sdk-web → SDK 源码（dist 未构建；与 esbuild bundle 的 alias 同一目标），
//   子入口 ./vue3 按包 exports 映射同源
const here = (p: string): string => fileURLToPath(new URL(p, import.meta.url));

export default defineConfig({
  resolve: {
    alias: [
      { find: /^vue$/, replacement: here('./node_modules/vue') },
      { find: /^@vue\/test-utils$/, replacement: here('./node_modules/@vue/test-utils') },
      { find: /^@nl-observe\/sdk-web$/, replacement: here('../../sdk-web/src/index.ts') },
      { find: /^@nl-observe\/sdk-web\/vue3$/, replacement: here('../../sdk-web/src/vue3.ts') },
    ],
  },
  test: {
    // 根聚合经目录引用本文件；显式命名保证 --project 过滤稳定
    name: 'poc-micro-app-capture',
    environment: 'jsdom',
    include: ['test/**/*.spec.ts'],
    testTimeout: 60_000,
    // worker-shim 经 setupFiles 集中保证先于一切测试模块求值（rc.32 模块顶层
    // Proxy(window.Worker)，jsdom 无 Worker 则导入即抛）——不再依赖各 spec 手工
    // 维护导入顺序（评审 m-4）
    setupFiles: [here('./src/worker-shim.ts')],
    // rc.32 探针的品牌校验 unhandled rejection 由 capture-rc32.spec.ts 在探针内
    // 自捕获（process 级监听器换装 + 文案断言），不设项目级
    // dangerouslyIgnoreUnhandledErrors——主验收文件（capture-0811/control/fallback）
    // 保持对真实未处理异常的敏感（评审 arch m-1 / sec M-1 / corr Min-1）
  },
});
