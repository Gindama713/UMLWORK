<script setup>
import { onMounted, ref } from 'vue'

const services = ref([
  { name: '车位资源', path: '/api/v1/spaces/status', state: '待检查' },
  { name: '出入场管理', path: '/api/v1/access/status', state: '待检查' },
  { name: '计费支付', path: '/api/v1/billing/status', state: '待检查' },
  { name: '预约月卡', path: '/api/v1/passes/status', state: '待检查' },
  { name: '运营分析', path: '/api/v1/analytics/status', state: '待检查' },
])
const checking = ref(false)

async function checkServices() {
  checking.value = true
  await Promise.all(services.value.map(async (service) => {
    try {
      const response = await fetch(service.path, { cache: 'no-store' })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const result = await response.json()
      service.state = result.code === 'OK' ? '已连接' : '响应异常'
    } catch {
      service.state = '未连接'
    }
  }))
  checking.value = false
}

onMounted(checkServices)
</script>

<template>
  <main class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">软件建模技术大作业 · 第 14 题</p>
        <h1>机场智慧停车与出行服务平台</h1>
        <p class="subtitle">工程起步检查页。当前只检查网关与服务连通，停车业务功能尚未实现。</p>
      </div>
      <el-button type="primary" :loading="checking" @click="checkServices">重新检查</el-button>
    </header>

    <section class="service-grid" aria-label="服务连通状态">
      <el-card v-for="service in services" :key="service.path" shadow="never">
        <div class="service-row">
          <strong>{{ service.name }}</strong>
          <el-tag :type="service.state === '已连接' ? 'success' : service.state === '未连接' ? 'danger' : 'info'">
            {{ service.state }}
          </el-tag>
        </div>
        <code>{{ service.path }}</code>
      </el-card>
    </section>

    <el-alert
      title="下一步：确认业务规则与接口后，再实现入场、占位、出场、计费、支付和释放车位的完整流程。"
      type="info"
      :closable="false"
      show-icon
    />
  </main>
</template>
