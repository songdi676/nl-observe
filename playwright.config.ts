/**
 * T23 Playwright E2E 配置（详设 §6.4，env-gated）。
 *
 * 环境变量：
 * - E2E_BACKEND_URL（env-gate，必填才跑）：查询服务基址，如 http://10.1.8.33:8081；
 *   未设置 → 全部 spec skip（内网 Kafka/Databend 可达性未知，待澄清 #7）
 * - E2E_OTLP_URL（可选）：OTLP/HTTP traces 端点；缺省按接收端 observe.otlp.port
 *   默认 4318 从 E2E_BACKEND_URL 同主机派生（详设 §5 receiver 配置），
 *   E2E_OTLP_PORT 可单独覆盖派生端口
 * - E2E_HOST_PORT（可选）：vue3 测试宿主页端口，缺省 4173（宿主页地址固定为
 *   http://127.0.0.1:<该端口>；host/vite.config.ts 读同一变量，三处口径一致。
 *   多 worktree 并行跑 E2E 时每个 worktree 须设不同端口——评审 arch M-1）
 *
 * 宿主页（tests/e2e/host，vite dev + mock 后端中间件）仅在 env-gate 打开时由
 * webServer 拉起——未设置 E2E_BACKEND_URL 时全部 skip，不起任何进程。
 */
import { defineConfig, devices } from '@playwright/test';

const e2eEnabled = Boolean(process.env.E2E_BACKEND_URL);
const hostPort = process.env.E2E_HOST_PORT ?? '4173';
const hostUrl = `http://127.0.0.1:${hostPort}`;

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: ['*.spec.ts'],
  // 复合预算（评审 arch m-3）：full-chain 最多串行 3 次 pollUntil（缺省 60s/次）
  // + 页内操作 ≈10s，须 < 本超时，保证 pollUntil 的「轮询超时（label）：最后一次
  // 错误」诊断先于 Playwright 通用超时触发
  timeout: 240_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1, // 两 spec 共享宿主端口与（内网）后端写入，串行避免互相干扰
  reporter: 'list',
  use: {
    baseURL: hostUrl,
    headless: true,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        launchOptions: {
          // 接收端（collector-embedded 1.2.1 + OtlpSecurityFilter）无 CORS 支持
          // （不处理 OPTIONS、不发 Access-Control-*），而 SDK sendBeacon 的
          // application/json Blob 在跨源浏览器里会触发 preflight 被拦（chromium 156 实测）。
          // E2E 浏览器关闭 web security：OTLP 载荷原样直达（content-type application/json，
          // 与接收端 isJsonContent 判定一致）；CORS 策略本身不在本测试范围——生产跨源部署
          // 需同源代理或接收端补 CORS（T23 交付说明记录该后端缺口）。
          args: ['--disable-web-security'],
        },
      },
    },
  ],
  webServer: e2eEnabled
    ? {
        command: 'npm run dev',
        cwd: './tests/e2e/host',
        url: hostUrl,
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      }
    : undefined,
});
