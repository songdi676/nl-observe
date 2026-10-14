<template>
  <header class="app-header">
    <span>NL Observe</span>
    <nav>
      <RouterLink to="/businesses">
        业务列表
      </RouterLink>
      <RouterLink to="/traces">
        流程
      </RouterLink>
      <RouterLink to="/metrics">
        指标
      </RouterLink>
    </nav>
  </header>
  <main>
    <RouterView />
  </main>
</template>
