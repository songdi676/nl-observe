/**
 * micro-app 1.0.0-rc.32（npm 别名包 'micro-app-rc'）的最小类型面。
 *
 * 该包自带的 index.d.ts 只声明 ambient module '@micro-zoe/micro-app'（与 0.8.11 同名冲突），
 * 别名导入无法复用——此处按 POC 实际用到的 rc.32 API 手工声明（renderApp 命令式挂载、
 * 'disable-sandbox' 开关、unmountApp destroy）。API 面以 lib/index.d.ts 实测为准。
 */
declare module 'micro-app-rc' {
  interface RcRenderAppOptions {
    name: string;
    url: string;
    container: string | Element;
    'disable-sandbox'?: boolean;
    [key: string]: unknown;
  }
  interface RcMicroApp {
    start(options?: Record<string, unknown>): void;
    renderApp(options: RcRenderAppOptions): Promise<boolean>;
    unmountApp(appName: string, options?: { destroy?: boolean }): Promise<boolean>;
  }
  const microApp: RcMicroApp;
  export default microApp;
}
