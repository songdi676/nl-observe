/**
 * nl-observe 演示宿主页（deploy/docker/demo-host，8083 由 nginx 伺服）：
 * 按钮驱动完整办理流程（签入 → 业务 A ORDER + mock fetch → 切业务 B PAY
 * 自动挂起 A → 恢复/结束），SDK 直接引 sdk-web/src 源码（vite alias），
 * OTLP 走同源 /v1（nginx 代理至 receiver:4318，绕接收端 CORS 缺口）。
 * 日常上报依赖 BSP 周期导出 + 页面卸载冲刷；「冲刷」按钮可手动 shutdown 强制上报。
 */
import { createClient } from '@nl-observe/sdk-web';

const params = new URLSearchParams(window.location.search);
const customerIdInput = document.querySelector<HTMLInputElement>('#customer');
const statusEl = document.querySelector<HTMLElement>('#status');

const customerId = params.get('customer') ?? `c-demo-${Date.now().toString(36)}`;
if (customerIdInput) customerIdInput.value = customerId;

const client = createClient({
  serviceName: 'demo-host',
  customerId,
  endpoint: '/v1/traces',
});

const flags: string[] = [`INITIALIZED`, `CUSTOMER=${customerId}`];
const bizIds: { [key: string]: string } = {};

function render(): void {
  if (!statusEl) return;
  const parts = [...flags];
  for (const label of Object.keys(bizIds)) {
    const id = bizIds[label];
    parts.push(`${label}=${id ? client.businessState(id) ?? 'ENDED' : 'NONE'}`);
  }
  statusEl.textContent = parts.join(' ');
}

function flag(f: string): void {
  flags.push(f);
  render();
}

function currentCustomer(): string {
  return customerIdInput?.value.trim() || customerId;
}

async function onFetch(): Promise<void> {
  // 真实 fetch（nginx 同源 /mock-api 返回 JSON）→ SDK fetch 包装产生 REST span
  const res = await fetch('/mock-api/orders', { method: 'GET' });
  flag(res.ok ? 'FETCHED' : 'FETCH_FAILED');
}

function onAction(action: string): void {
  switch (action) {
    case 'signin':
      client.signIn(currentCustomer());
      flag(`SIGNED_IN(${currentCustomer()})`);
      break;
    case 'start-a':
      bizIds.A = client.startBusiness('ORDER');
      flag('A_STARTED');
      break;
    case 'fetch':
      void onFetch();
      break;
    case 'start-b':
      // startBusiness 自动挂起 A（业务状态机语义）
      bizIds.B = client.startBusiness('PAY');
      flag('B_STARTED');
      break;
    case 'resume-a':
      if (bizIds.A) client.resumeBusiness(bizIds.A);
      flag('A_RESUMED');
      break;
    case 'end-a':
      if (bizIds.A) client.endBusiness(bizIds.A, { status: 'success' });
      flag('A_ENDED');
      break;
    case 'end-b':
      if (bizIds.B) client.endBusiness(bizIds.B, { status: 'success' });
      flag('B_ENDED');
      break;
    case 'flush':
      void client.shutdown().then(() => flag('FLUSHED'));
      break;
  }
}

document.addEventListener('click', (ev) => {
  const btn = (ev.target as HTMLElement | null)?.closest<HTMLButtonElement>('button[data-action]');
  if (btn) onAction(btn.dataset.action ?? '');
});

render();
