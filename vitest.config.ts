import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// 仓库根聚合 POC 宿主 + sdk-web 双测试宿主 + sdk-web core 单测（core.spec + business.spec + flush.spec + rest.spec）：
// 一次运行产出根目录 coverage/coverage-summary.json（质量门读取点）
// 注：sdk-web 的构建产物测试（test/dist-loads.spec.ts）不在根聚合——它依赖 dist 存在，
// 经 npm test -w @nl-observe/sdk-web（pretest 先 tsup 构建）单独跑。
// 测试宿主（test/vue2-host、vue3-host）为独立 npm 工程（非 workspace 成员，见根 package.json
// description），在各自目录 npm ci 安装后由根 projects 聚合其用例。
// 已知 hoisting 问题（LaunchNotes）：根聚合时 vite 将裸依赖 'vue' 统一解析到根 node_modules
// （vue@2.7.16），vue3 宿主因此拿到 vue2 运行时 → withDirectives 缺失。
// 对策：为两个宿主项目 alias 'vue'/'@vue/test-utils' 到各自宿主本地 node_modules，
// 保证 spec 与 sdk-web/src 的 'vue' 导入落在同一 Vue 实例（vue2/vue3 版本各归其宿）。
const hostDep = (hostDir: string, pkg: string): string =>
  fileURLToPath(new URL(`${hostDir}/node_modules/${pkg}`, import.meta.url));

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
        resolve: {
          alias: [
            { find: /^vue$/, replacement: hostDep('./sdk-web/test/vue2-host', 'vue') },
            { find: /^@vue\/test-utils$/, replacement: hostDep('./sdk-web/test/vue2-host', '@vue/test-utils') },
          ],
        },
        test: {
          name: 'sdk-web-vue2-host',
          root: './sdk-web/test/vue2-host',
          environment: 'jsdom',
          include: ['*.spec.ts'],
        },
      },
      {
        extends: true,
        resolve: {
          alias: [
            { find: /^vue$/, replacement: hostDep('./sdk-web/test/vue3-host', 'vue') },
            { find: /^@vue\/test-utils$/, replacement: hostDep('./sdk-web/test/vue3-host', '@vue/test-utils') },
          ],
        },
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
          include: ['test/core.spec.ts', 'test/business.spec.ts', 'test/flush.spec.ts', 'test/rest.spec.ts'],
        },
      },
      // T21 POC：micro-app 沙箱捕获率验证（workspace 成员）。经目录引用其自带
      // vitest.config.ts——dangerouslyIgnoreUnhandledErrors（rc.32 探针容忍，见该文件注释）
      // 在 vitest 5 是 NonProjectOptions，内联 project 条目类型上不允许，配置文件顶层合法。
      './poc/micro-app-capture',
    ],
    coverage: {
      provider: 'v8',
      include: ['poc/*/src/**/*.ts', 'sdk-web/src/**/*.ts'],
      reporter: ['text', 'json-summary', 'lcov'],
      reportsDirectory: './coverage',
    },
  },
});
