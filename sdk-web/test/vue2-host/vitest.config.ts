import { defineConfig } from 'vitest/config';

// vue2 测试宿主独立可跑（npm test -w sdk-web-vue2-host）；根 vitest.config.ts 亦以 project 聚合
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['*.spec.ts'],
  },
});
