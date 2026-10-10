<script setup>
import AppIcon from '../components/AppIcon.vue'
import SpaceMap from '../components/SpaceMap.vue'
import TrafficChart from '../components/TrafficChart.vue'

// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const {
  page,
  types,
  busy,
  spaces,
  chargers,
  dashboardReport,
  dashboardError,
  resourcesReady,
  metrics,
  occupancy,
  typeIcon,
  entry,
  search,
  adminSpace,
  analytics,
  findFromOverview,
} = props.workspace
</script>

<template>
  <section class="metric-grid" aria-label="资源统计">
    <article class="metric-card accent">
      <div class="metric-label"><span>可用车位</span><AppIcon name="parking" /></div>
      <strong>{{ resourcesReady ? metrics.available : '—' }}<small>个</small></strong>
      <p>
        {{
          resourcesReady
            ? `全场 ${metrics.total} 个 · 停用 ${metrics.offline} 个`
            : '等待资源服务响应'
        }}
      </p>
    </article>
    <article class="metric-card">
      <div class="metric-label"><span>在场占用率</span><AppIcon name="car" /></div>
      <strong>{{ resourcesReady ? occupancy : '—' }}<small>%</small></strong>
      <p>
        {{
          resourcesReady ? `${metrics.occupied} 个车位正在使用 / 全部物理车位` : '等待资源服务响应'
        }}
      </p>
    </article>
    <article class="metric-card">
      <div class="metric-label"><span>近 24 小时入场</span><AppIcon name="arrowUp" /></div>
      <strong
        >{{ dashboardError ? '—' : (dashboardReport?.totalEntries ?? '—')
        }}<small>辆</small></strong
      >
      <p>基于实际入场记录</p>
    </article>
    <article class="metric-card">
      <div class="metric-label"><span>充电桩使用</span><AppIcon name="bolt" /></div>
      <strong
        >{{ resourcesReady ? chargers.filter((c) => c.status === 'IN_USE').length : '—'
        }}<small>/ {{ resourcesReady ? chargers.length : '—' }}</small></strong
      >
      <p>当前正在服务的充电桩</p>
    </article>
  </section>
  <div class="overview-grid">
    <section class="panel map-panel">
      <div class="panel-heading">
        <div>
          <span class="section-kicker">01 / PARKING MAP</span>
          <h2>停车分区</h2>
        </div>
        <button class="text-button" @click="page = 'resources'">
          管理资源<AppIcon name="arrowUp" />
        </button>
      </div>
      <SpaceMap
        v-if="resourcesReady"
        :spaces="spaces"
        @select="(space) => (adminSpace.spaceId = space.spaceId)"
      />
      <p v-else class="empty-state">资源暂未加载</p>
    </section>
    <aside class="workbench-rail">
      <section class="arrival-card">
        <div class="arrival-top"><span>READY FOR ARRIVAL</span><AppIcon name="plane" /></div>
        <h2>下一辆车<br />从这里开始。</h2>
        <p>识别车牌 · 分配车位 · 登记入场</p>
        <button class="primary-link" @click="page = 'entry'">
          登记车辆入场<AppIcon name="arrow" />
        </button>
      </section>
      <section class="panel quick-find">
        <div class="panel-heading">
          <h2>车停在哪里？</h2>
          <AppIcon name="pin" />
        </div>
        <p class="muted">输入车牌，查询在场位置。</p>
        <el-input
          v-model="search.plateNumber"
          aria-label="总览寻车车牌"
          placeholder="输入车牌号"
          @keyup.enter="findFromOverview"
        /><el-button :loading="busy" @click="findFromOverview"
          ><AppIcon name="search" />查找车辆</el-button
        >
      </section>
      <button class="owner-link" @click="page = 'passes'">
        <span class="owner-icon"><AppIcon name="ticket" /></span
        ><span><strong>提前安排下一程</strong><small>车位预约 / 月卡权益</small></span
        ><AppIcon name="arrowUp" />
      </button>
    </aside>
  </div>
  <div class="overview-bottom">
    <section class="panel chart-panel">
      <div class="panel-heading">
        <div>
          <span class="section-kicker">02 / TRAFFIC</span>
          <h2>近 24 小时车流</h2>
        </div>
        <button class="text-button" @click="page = 'analytics'">
          查看报表<AppIcon name="arrowUp" />
        </button>
      </div>
      <el-alert
        v-if="dashboardError"
        :title="dashboardError"
        type="warning"
        :closable="false"
      /><TrafficChart v-else :report="dashboardReport" />
    </section>
    <section class="panel">
      <div class="panel-heading">
        <div>
          <span class="section-kicker">03 / AVAILABILITY</span>
          <h2>分类余量</h2>
        </div>
        <span class="muted">可用 / 总量</span>
      </div>
      <div class="resource-lines">
        <div v-for="(title, type) in types" :key="type">
          <span><AppIcon :name="typeIcon(type)" />{{ title }}</span
          ><strong
            >{{
              resourcesReady
                ? spaces.filter((s) => s.type === type && s.status === 'AVAILABLE').length
                : '—'
            }}<small>
              /
              {{ resourcesReady ? spaces.filter((s) => s.type === type).length : '—' }}</small
            ></strong
          >
        </div>
      </div>
      <p class="map-caption">未来预约保留由预约服务按时间窗核验。</p>
    </section>
  </div>
</template>
