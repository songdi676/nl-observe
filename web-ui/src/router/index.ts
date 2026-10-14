import { createRouter, createWebHistory } from 'vue-router'
import type { RouterHistory, RouteRecordRaw } from 'vue-router'
import BusinessesPage from '../pages/BusinessesPage.vue'
import TracePage from '../pages/TracePage.vue'
import MetricsPage from '../pages/MetricsPage.vue'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/businesses' },
  { path: '/businesses', name: 'businesses', component: BusinessesPage },
  { path: '/traces/:traceId?', name: 'trace', component: TracePage },
  { path: '/metrics', name: 'metrics', component: MetricsPage }
]

export function createAppRouter(history?: RouterHistory) {
  return createRouter({
    history: history ?? createWebHistory(),
    routes
  })
}
