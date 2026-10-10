<script setup>
import AppIcon from './components/AppIcon.vue'
import OverviewPage from './pages/OverviewPage.vue'
import ResourcesPage from './pages/ResourcesPage.vue'
import PassesPage from './pages/PassesPage.vue'
import EntryPage from './pages/EntryPage.vue'
import ChargingPage from './pages/ChargingPage.vue'
import BillingPage from './pages/BillingPage.vue'
import AnalyticsPage from './pages/AnalyticsPage.vue'
import { useParkingWorkspace } from './composables/useParkingWorkspace.js'

const workspace = useParkingWorkspace()
const {
  navigation,
  page,
  currentPage,
  services,
  busy,
  resourceError,
  updatedAt,
  lastAction,
  noticeType,
  connectedCount,
  output,
  action,
  checkServices,
  refreshOverview,
} = workspace
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="page = 'overview'"
        ><span class="brand-mark"><AppIcon name="plane" /></span>
        <div><strong>空港智停</strong><small>AERO / PARK</small></div></a
      >
      <p class="nav-caption">WORKSPACE / 业务工作台</p>
      <nav aria-label="业务导航">
        <button
          v-for="item in navigation"
          :key="item.id"
          :class="{ active: page === item.id }"
          :aria-current="page === item.id ? 'page' : undefined"
          @click="page = item.id"
        >
          <AppIcon :name="item.icon" />{{ item.label
          }}<AppIcon v-if="page === item.id" class="nav-arrow" name="arrow" />
        </button>
      </nav>
      <div class="sidebar-guide">
        <AppIcon name="plane" />
        <p>停好这一程<br />从容赴下一程</p>
        <span>PARK. CHARGE. GO.</span>
      </div>
      <div class="sidebar-footer">
        <span class="demo-dot"></span>本地课程演示
        <p>车牌识别、支付及发票为模拟</p>
        <small>软件建模技术 · 第 14 题</small>
      </div>
    </aside>
    <main class="workspace" :aria-busy="busy">
      <header class="topbar">
        <span class="breadcrumb"
          >机场智慧停车与出行服务平台 <span class="topbar-separator">/</span>
          <strong>{{ currentPage.label }}</strong></span
        >
        <details class="connection-menu">
          <summary>
            <span
              class="demo-dot"
              :class="{ disconnected: connectedCount < services.length }"
            ></span
            >服务连接 {{ connectedCount }}/{{ services.length }}
          </summary>
          <div class="connection-list">
            <div class="service-row" v-for="service in services" :key="service.path">
              <span>{{ service.name }}</span
              ><span class="service-state" :class="{ online: service.state === '已连接' }">{{
                service.state
              }}</span>
            </div>
            <button class="text-button" @click="checkServices">重新检查连接</button>
          </div>
        </details>
      </header>
      <div class="page">
        <header class="page-header">
          <div>
            <p class="eyebrow">AERO / PARK <span>OPERATIONS</span></p>
            <h1>{{ currentPage.label }}<span class="title-dot">.</span></h1>
            <p class="subtitle">{{ currentPage.hint }}</p>
          </div>
          <div class="header-tools">
            <small class="updated">资源快照<br />{{ updatedAt || '尚未加载' }}</small
            ><el-button :loading="busy" @click="action(refreshOverview)"
              ><AppIcon name="refresh" />刷新数据</el-button
            >
          </div>
        </header>
        <el-alert
          v-if="resourceError"
          :title="resourceError"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-alert
          v-if="lastAction && noticeType !== 'success'"
          :title="lastAction"
          :type="noticeType"
          show-icon
          closable
          @close="lastAction = ''"
        />

        <el-tabs v-model="page" class="business-pages">
          <el-tab-pane name="overview" label="运营总览">
            <OverviewPage :workspace="workspace" />
          </el-tab-pane>
          <el-tab-pane name="resources" label="车位资源">
            <ResourcesPage :workspace="workspace" />
          </el-tab-pane>

          <el-tab-pane name="passes" label="预约与月卡">
            <PassesPage :workspace="workspace" />
          </el-tab-pane>

          <el-tab-pane name="entry" label="入场与寻车">
            <EntryPage :workspace="workspace" />
          </el-tab-pane>

          <el-tab-pane name="charging" label="充电">
            <ChargingPage :workspace="workspace" />
          </el-tab-pane>

          <el-tab-pane name="billing" label="出场结算">
            <BillingPage :workspace="workspace" />
          </el-tab-pane>

          <el-tab-pane name="analytics" label="运营报表">
            <AnalyticsPage :workspace="workspace" />
          </el-tab-pane>
        </el-tabs>
        <details class="result">
          <summary>联调详情 · 最近一次接口结果</summary>
          <pre>{{ output }}</pre>
        </details>
        <footer class="page-footer">
          机场智慧停车与出行服务平台 <span>软件工程 24201718 姜苏豪 · 【AI-辅助】课程演示</span>
        </footer>
      </div>
    </main>
  </div>
</template>
