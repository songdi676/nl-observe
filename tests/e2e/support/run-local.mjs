/**
 * T23 本地离线自检入口（npm run test:e2e:local）：
 * 拉起 stub 后端（OTLP 4318 + 查询 8081，端口可经 E2E_STUB_*_PORT 覆盖）→
 * 以 E2E_BACKEND_URL=http://127.0.0.1:<queryPort> 跑 Playwright（宿主 webServer
 * 由 playwright.config.ts 拉起）→ 透传退出码。
 *
 * 注意：这是离线自检（验证用例逻辑与契约镜像），内网可达时验收口径为
 * E2E_BACKEND_URL=<真实查询服务> npm run test:e2e。
 */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import http from 'node:http';

const otlpPort = process.env.E2E_STUB_OTLP_PORT ?? '4318';
const queryPort = process.env.E2E_STUB_QUERY_PORT ?? '8081';

const stub = spawn(process.execPath, [fileURLToPath(new URL('./stub-backend.mjs', import.meta.url))], {
  env: { ...process.env, E2E_STUB_OTLP_PORT: otlpPort, E2E_STUB_QUERY_PORT: queryPort },
  stdio: 'inherit',
});

function probe(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port: Number(port), path: '/healthz' }, (res) => {
      res.resume();
      resolve(res.statusCode === 200);
    });
    req.on('error', () => resolve(false));
  });
}

let exitCode = 1;
try {
  const deadline = Date.now() + 10_000;
  for (;;) {
    if (await probe(queryPort)) break;
    if (Date.now() > deadline) throw new Error('stub 后端 10s 内未就绪');
    await new Promise((r) => setTimeout(r, 250));
  }

  const playwright = spawn('npx', ['playwright', 'test', ...process.argv.slice(2)], {
    env: {
      ...process.env,
      E2E_BACKEND_URL: `http://127.0.0.1:${queryPort}`,
      E2E_OTLP_URL: `http://127.0.0.1:${otlpPort}/v1/traces`,
      E2E_HOST_PORT: process.env.E2E_HOST_PORT ?? '4173',
    },
    stdio: 'inherit',
  });
  exitCode = await new Promise((resolve) => playwright.on('exit', (c) => resolve(c ?? 1)));
} catch (err) {
  console.error('[run-local] 启动失败:', err);
} finally {
  stub.kill();
}
process.exit(exitCode);
