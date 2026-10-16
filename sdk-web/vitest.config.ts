import { defineConfig } from 'vitest/config';

// 构建产物测试（node 环境）：需先 tsup 构建——npm test -w @nl-observe/sdk-web 的 pretest 已保证。
// 根 vitest projects 聚合 core.spec + business.spec（不聚合 dist-loads：根覆盖率跑不依赖 dist 存在）。
export default defineConfig({
  test: {
    environment: 'node',
    include: [
      'test/dist-loads.spec.ts',
      'test/core.spec.ts',
      'test/business.spec.ts',
      'test/flush.spec.ts',
      'test/rest.spec.ts',
    ],
  },
});
