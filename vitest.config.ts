import { defineConfig } from 'vitest/config';

// 仓库根聚合两个 POC 宿主工程：一次运行产出根目录 coverage/coverage-summary.json（质量门读取点）
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
    ],
    coverage: {
      provider: 'v8',
      include: ['poc/*/src/**/*.ts'],
      reporter: ['text', 'json-summary', 'lcov'],
      reportsDirectory: './coverage',
    },
  },
});
