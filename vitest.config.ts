import { defineConfig } from 'vitest/config';

// 仓库根聚合 POC 宿主 + sdk-web 双测试宿主 + sdk-web core 单测（core.spec + business.spec）：
// 一次运行产出根目录 coverage/coverage-summary.json（质量门读取点）
// 注：sdk-web 的构建产物测试（test/dist-loads.spec.ts）不在根聚合——它依赖 dist 存在，
// 经 npm test -w @nl-observe/sdk-web（pretest 先 tsup 构建）单独跑。
// 测试宿主（test/vue2-host、vue3-host）为独立 npm 工程（非 workspace 成员，见根 package.json
// description），在各自目录 npm ci 安装后由根 projects 聚合其用例。
export default defineConfig({
  test: {
    projects: [
      {
        extends: true,
        test: {
          name: 'sdk-vue2-init',
          root: './poc/sdk-vue2-init',
          environment: 'jsdom',
          include: ['test/**/*.spec.ts'],
        },
      },
      {
        extends: true,
        test: {
          name: 'sdk-vue3-init',
          root: './poc/sdk-vue3-init',
          environment: 'jsdom',
          include: ['test/**/*.spec.ts'],
        },
      },
      {
        extends: true,
        test: {
          name: 'sdk-web-vue2-host',
          root: './sdk-web/test/vue2-host',
          environment: 'jsdom',
          include: ['*.spec.ts'],
        },
      },
      {
        extends: true,
        test: {
          name: 'sdk-web-vue3-host',
          root: './sdk-web/test/vue3-host',
          environment: 'jsdom',
          include: ['*.spec.ts'],
        },
      },
      {
        extends: true,
        test: {
          name: 'sdk-web-core',
          root: './sdk-web',
          environment: 'node',
          include: ['test/core.spec.ts', 'test/business.spec.ts'],
        },
      },
    ],
    coverage: {
      provider: 'v8',
      include: ['poc/*/src/**/*.ts', 'sdk-web/src/**/*.ts'],
      reporter: ['text', 'json-summary', 'lcov'],
      reportsDirectory: './coverage',
    },
  },
});
