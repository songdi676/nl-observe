<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <section class="page trace-page">
    <h1>业务流程时间线</h1>
    <p class="trace-id">
      traceId：{{ route.params.traceId ?? '（待从业务列表选择）' }}
    </p>
    <p class="placeholder">
      span 树瀑布/时间线将在 T19 接入 GET /api/observe/customers/{customerId}/traces/{traceId}
    </p>
  </section>
</template>
