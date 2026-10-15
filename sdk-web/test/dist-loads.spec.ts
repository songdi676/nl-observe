/**
 * T12 测试用例（test_cases: 构建产物测试）：require(esm/cjs) 均可加载。
 * es/cjs 双格式产物（dist/*.js 与 dist/*.cjs）三个入口逐一加载并断言导出形状。
 */
import { describe, it, expect } from 'vitest';
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { existsSync } from 'node:fs';

const requireCjs = createRequire(import.meta.url);
const distDir = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'dist');

describe('sdk-web 构建产物（es/cjs 双格式）', () => {
  it('dist 三入口双格式均存在（先构建：npm run build -w @nl-observe/sdk-web）', () => {
    for (const entry of ['index', 'vue2', 'vue3']) {
      expect(existsSync(resolve(distDir, `${entry}.js`)), `${entry}.js`).toBe(true);
      expect(existsSync(resolve(distDir, `${entry}.cjs`)), `${entry}.cjs`).toBe(true);
    }
  });

  it('cjs：require() 三入口均可加载', () => {
    const index = requireCjs(resolve(distDir, 'index.cjs'));
    expect(typeof index.createClient).toBe('function');
    expect(typeof index.NlObserveClient).toBe('function');
    expect(index.SDK_VERSION).toBe('0.0.0');

    const vue2 = requireCjs(resolve(distDir, 'vue2.cjs'));
    expect(typeof vue2.registerVue2Directives).toBe('function');
    expect(typeof vue2.createClient).toBe('function'); // core 共享（质疑者1 R1 双构建成本对策）

    const vue3 = requireCjs(resolve(distDir, 'vue3.cjs'));
    expect(typeof vue3.registerVue3Directives).toBe('function');
    expect(typeof vue3.createClient).toBe('function');
  });

  it('esm：import() 三入口均可加载', async () => {
    const index = await import(pathToFileURL(resolve(distDir, 'index.js')).href);
    expect(typeof index.createClient).toBe('function');
    expect(index.SDK_VERSION).toBe('0.0.0');

    const vue2 = await import(pathToFileURL(resolve(distDir, 'vue2.js')).href);
    expect(typeof vue2.registerVue2Directives).toBe('function');

    const vue3 = await import(pathToFileURL(resolve(distDir, 'vue3.js')).href);
    expect(typeof vue3.registerVue3Directives).toBe('function');
  });
});
