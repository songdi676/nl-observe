<script setup lang="ts">
import { ref } from 'vue'

const customerId = ref('')
const from = ref('')
const to = ref('')
const bizCode = ref('')
</script>

<template>
  <section class="page businesses-page">
    <h1>业务列表</h1>
    <form class="filters">
      <label>
        客户编号
        <input
          v-model="customerId"
          name="customerId"
          type="text"
        >
      </label>
      <label>
        开始日期
        <input
          v-model="from"
          name="from"
          type="date"
        >
      </label>
      <label>
        结束日期
        <input
          v-model="to"
          name="to"
          type="date"
        >
      </label>
      <label>
        业务码
        <input
          v-model="bizCode"
          name="bizCode"
          type="text"
        >
      </label>
      <button
        type="button"
      >
        查询
      </button>
    </form>
    <p class="placeholder">
      业务列表数据将在 T19 接入 GET /api/observe/customers/{customerId}/businesses
    </p>
  </section>
</template>
