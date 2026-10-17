<script setup lang="ts">
/**
 * T23 E2E vue3 宿主页（详设 §6.4）：按钮驱动办理流程，Playwright 逐步点击：
 * 签入客户 → 业务 A（ORDER，真实 fetch 至 /mock-api/orders）→ 切业务 B（PAY，
 * A 自动挂起）→ 恢复 A 结束 A → 结束 B。
 *
 * 查询参数：customer=客户号（init resource 用）、otlp=OTLP/HTTP traces 端点。
 * 状态串（data-testid=host-status）：事件 flag + 各业务状态（ACTIVE/SUSPENDED/ENDED），
 * 供 spec 在页内先行断言（如切 B 后 A=SUSPENDED）。
 * window.__e2eFlush()：client.shutdown() 冲刷 BSP/metric reader（OTLP 尽力上报）。
 */
import { computed, onMounted, ref } from 'vue';
import { createClient } from '@nl-observe/sdk-web';

const params = new URLSearchParams(window.location.search);
const customerId = params.get('customer') ?? 'c-e2e-default';
// 安全评审 sec L-1 缓解：E2E 浏览器带 --disable-web-security（绕接收端 CORS 缺口，
// 该浏览器可跨源向任意地址发请求），宿主页仅绑 127.0.0.1，但 otlp 查询参数未校验
// 时本机进程可借该浏览器作跳板。此处钉死协议白名单——非绝对 http(s) URL 一律
// 丢弃（拦截 file:/data: 等非 HTTP 与畸形值）；otlp 值由可信的 E2E runner 传入
// （127.0.0.1 或内网 receiver），主机名白名单不做硬编码（内网地址不静态可知）。
// 残留风险（可指向任意 IP 主机）记录于 tests/e2e/README.md，完整解法（vite 同源
// 代理替代 --disable-web-security）属接收端 CORS 缺口修复后跟进（T06 侧）。
const rawOtlp = params.get('otlp') ?? '';
let otlp = rawOtlp;
if (rawOtlp) {
  try {
    const u = new URL(rawOtlp);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') otlp = '';
  } catch {
    otlp = '';
  }
}
const otlpRejected = rawOtlp !== '' && otlp === '';

const client = createClient({
  serviceName: 'e2e-vue3-host',
  customerId,
  endpoint: otlp || undefined,
});

const flags = ref<string[]>(['INITIALIZED', `CUSTOMER=${customerId}`, ...(otlpRejected ? ['OTLP_REJECTED'] : [])]);
const bizA = ref<string>();
const bizB = ref<string>();

function pushFlag(flag: string): void {
  flags.value = [...flags.value, flag];
}

function stateOf(bizId?: string): string {
  if (!bizId) return '';
  return client.businessState(bizId) ?? 'ENDED';
}

const statusLine = computed(() => {
  const parts: string[] = [...flags.value];
  if (bizA.value) parts.push(`A=${stateOf(bizA.value)}`);
  if (bizB.value) parts.push(`B=${stateOf(bizB.value)}`);
  return parts.join(' ');
});

function onSignIn(): void {
  client.signIn(customerId);
  pushFlag('SIGNED_IN');
}

function onStartA(): void {
  bizA.value = client.startBusiness('ORDER');
  pushFlag('A_STARTED');
}

async function onFetch(): Promise<void> {
  // 真实跨进程 fetch（vite 进程 mock 后端）→ SDK fetch 包装产生 REST span
  const res = await fetch('/mock-api/orders', { method: 'GET' });
  pushFlag(res.ok ? 'FETCHED' : 'FETCH_FAILED');
}

function onStartB(): void {
  // startBusiness 自动挂起 A（T13 状态机语义）
  bizB.value = client.startBusiness('PAY');
  pushFlag('B_STARTED');
}

function onResumeA(): void {
  if (bizA.value) client.resumeBusiness(bizA.value);
  pushFlag('A_RESUMED');
}

function onEndA(): void {
  if (bizA.value) client.endBusiness(bizA.value, { status: 'success' });
  pushFlag('A_ENDED');
}

function onEndB(): void {
  if (bizB.value) client.endBusiness(bizB.value, { status: 'success' });
  pushFlag('B_ENDED');
}

// Playwright 收尾冲刷：shutdown 触发 provider/meterProvider forceFlush（OTLP 尽力上报）
onMounted(() => {
  (window as unknown as { __e2eFlush?: () => Promise<void> }).__e2eFlush = () => client.shutdown();
});
</script>

<template>
  <main class="e2e-host">
    <h1>nl-observe E2E 宿主（vue3）</h1>
    <pre class="status" data-testid="host-status">{{ statusLine }}</pre>
    <button type="button" data-testid="btn-signin" @click="onSignIn">签入客户</button>
    <button type="button" data-testid="btn-start-a" @click="onStartA">业务 A（ORDER）</button>
    <button type="button" data-testid="btn-fetch" @click="onFetch">发起请求（mock 后端）</button>
    <button type="button" data-testid="btn-start-b" @click="onStartB">切业务 B（PAY）</button>
    <button type="button" data-testid="btn-resume-a" @click="onResumeA">恢复 A</button>
    <button type="button" data-testid="btn-end-a" @click="onEndA">结束 A</button>
    <button type="button" data-testid="btn-end-b" @click="onEndB">结束 B</button>
  </main>
</template>
