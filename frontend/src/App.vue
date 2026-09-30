<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import SpaceMap from './components/SpaceMap.vue'
import TrafficChart from './components/TrafficChart.vue'
import AppIcon from './components/AppIcon.vue'

const navigation = [
  { id: 'overview', label: '运营总览', icon: 'dashboard', hint: '每一次抵达，从这里开始。' },
  { id: 'entry', label: '入场与寻车', icon: 'car', hint: '登记车辆，找到属于它的位置。' },
  { id: 'passes', label: '预约与月卡', icon: 'ticket', hint: '提前留好车位，让出行从容一点。' },
  { id: 'charging', label: '充电服务', icon: 'bolt', hint: '停好车，也为下一程充好电。' },
  { id: 'billing', label: '出场结算', icon: 'wallet', hint: '费用清楚，出场顺畅。' },
  { id: 'resources', label: '车位资源', icon: 'parking', hint: '楼层、区域与车位，一目了然。' },
  { id: 'analytics', label: '运营报表', icon: 'chart', hint: '从真实车流，读懂运营节奏。' },
]
const page = ref('overview')
const currentPage = computed(() => navigation.find(item => item.id === page.value))
const types = { NORMAL: '普通车位', ACCESSIBLE: '无障碍车位', CHARGING: '充电车位', RESERVATION: '预约车位' }
const states = { AVAILABLE: '可用', OCCUPIED: '占用', OUT_OF_SERVICE: '停用', IN_USE: '使用中', PARKED: '在场', EXIT_PENDING_PAYMENT: '待支付', PAID_PENDING_RELEASE: '已付待释放', CLOSED: '已出场', PENDING_PAYMENT: '待预付', CONFIRMED: '已确认', USED: '已核销', CANCELLED: '已取消', EXPIRED: '已过期', ACTIVE: '使用中', FINISHED: '已结束', PAID: '已支付', UNPAID: '待支付', REQUESTED: '已申请' }
const label = state => states[state] || state
const feeNames = { PARKING_DAY: '自然日停车费', LOST_CARD: '丢卡附加费', LONG_STAY: '超长停车附加费', DISCOUNT: '停车优惠', PREPAID_APPLIED: '预付抵扣', PREPAID_REFUND: '预付差额退款', CHARGING: '充电费用' }

const services = ref([
  { name: '车位资源', path: '/api/v1/spaces/status', state: '待检查' },
  { name: '出入场管理', path: '/api/v1/access/status', state: '待检查' },
  { name: '计费支付', path: '/api/v1/billing/status', state: '待检查' },
  { name: '预约月卡', path: '/api/v1/passes/status', state: '待检查' },
  { name: '运营分析', path: '/api/v1/analytics/status', state: '待检查' },
])
const busy = ref(false)
const spaces = ref([])
const chargers = ref([])
const session = ref(null)
const bill = ref(null)
const report = ref(null)
const reservation = ref(null)
const monthly = ref(null)
const monthlyLedger = ref([])
const charging = ref(null)
const invoiceResult = ref(null)
const exitResult = ref(null)
const dashboardReport = ref(null)
const dashboardError = ref('')
const resourcesReady = ref(false)
const resourceError = ref('')
const updatedAt = ref('')
const metrics = computed(() => ({
  total: spaces.value.length,
  available: spaces.value.filter(s => s.status === 'AVAILABLE').length,
  occupied: spaces.value.filter(s => s.status === 'OCCUPIED').length,
  offline: spaces.value.filter(s => s.status === 'OUT_OF_SERVICE').length,
}))
const occupancy = computed(() => metrics.value.total ? Math.round(metrics.value.occupied / metrics.value.total * 100) : 0)
const lastAction = ref('')
const noticeType = ref('success')
const connectedCount = computed(() => services.value.filter(s => s.state === '已连接').length)
const settlementStep = computed(() => (session.value?.status === 'CLOSED' || exitResult.value?.status === 'CLOSED') ? 4 : bill.value?.status === 'PAID' ? 3 : bill.value ? 1 : 0)
const typeIcon = type => ({ NORMAL: 'parking', ACCESSIBLE: 'accessible', CHARGING: 'bolt', RESERVATION: 'ticket' })[type]
const entry = reactive({ plateNumber: '', spaceType: 'NORMAL', reservationId: '', entryTime: '' })
const search = reactive({ plateNumber: '' })
const exit = reactive({ sessionId: '', exitTime: '', exceptionType: 'NONE', operator: '' })
const reserve = reactive({ plateNumber: '', spaceId: '', startTime: '', endTime: '' })
const pass = reactive({ plateNumber: '', startTime: '' })
const charge = reactive({ chargerId: '', parkingSessionId: '', startTime: '', endTime: '', energyKwh: '' })
const adminSpace = reactive({ type: 'NORMAL', floor: '', zone: '', number: '', spaceId: '', status: 'OUT_OF_SERVICE' })
const invoice = reactive({ billId: '', invoiceTitle: '', taxNumber: '' })
const analytics = reactive({ from: '', to: '', granularity: 'HOUR' })
const output = ref('操作结果会显示在这里。')

function iso(value) {
  if (!value) return undefined
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) throw new Error('请输入有效时间')
  return date.toISOString()
}
function money(cents) {
  return cents == null ? '—' : `¥${(Number(cents) / 100).toFixed(2)}`
}
function key() { return crypto.randomUUID() }
const pendingKeys = new Map()
function dateTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
}
function localInput(date) {
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 23)
}

async function api(path, method = 'GET', body) {
  const payload = method === 'GET' ? undefined : JSON.stringify(body ?? {})
  const request = `${method}:${path}:${payload}`
  if (method !== 'GET' && !pendingKeys.has(request)) pendingKeys.set(request, key())
  const response = await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(method === 'GET' ? {} : { 'Idempotency-Key': pendingKeys.get(request) }),
    },
    body: payload,
    cache: 'no-store',
  })
  const result = await response.json()
  if (response.status < 500) pendingKeys.delete(request)
  if (!response.ok || result.code !== 'OK') throw new Error(result.message || result.code || `HTTP ${response.status}`)
  return result.data
}
async function action(job, showPaymentStatus = false) {
  if (busy.value) return
  busy.value = true
  try {
    const result = await job()
    output.value = JSON.stringify(result, null, 2)
    const status = showPaymentStatus ? result?.status : undefined
    noticeType.value = ['PAID_PENDING_RELEASE', 'EXIT_PENDING_PAYMENT'].includes(status) ? 'warning' : 'success'
    lastAction.value = status === 'PAID_PENDING_RELEASE' ? '支付已成功，车位尚未释放；请重试完成出场。'
      : status === 'EXIT_PENDING_PAYMENT' ? '支付未成功，账单保留待支付状态，可以重试。' : '操作完成，结果已更新。'
    ElMessage({ type: noticeType.value, message: lastAction.value })
    return result
  } catch (error) {
    output.value = error.message
    noticeType.value = 'error'
    lastAction.value = error.message
    ElMessage.error(error.message)
  } finally {
    busy.value = false
  }
}
async function checkServices() {
  await Promise.all(services.value.map(async (service) => {
    try { await api(service.path); service.state = '已连接' }
    catch { service.state = '未连接' }
  }))
}
async function loadResources() {
  try {
    const [spaceData, chargerData] = await Promise.all([api('/api/v1/spaces'), api('/api/v1/spaces/chargers')])
    spaces.value = spaceData
    chargers.value = chargerData
    resourcesReady.value = true
    resourceError.value = ''
    updatedAt.value = dateTime(new Date())
  } catch (error) { resourceError.value = `资源刷新失败：${error.message}` }
}
async function refreshOverview() {
  await Promise.all([checkServices(), loadResources(), loadTrafficPreview()])
}
async function loadTrafficPreview() {
  try {
    const to = new Date(), from = new Date(to.getTime() - 24 * 60 * 60 * 1000)
    dashboardReport.value = await api(`/api/v1/analytics/traffic-preview?${new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), granularity: 'HOUR' })}`)
    dashboardError.value = ''
  } catch (error) { dashboardError.value = `车流统计暂不可用：${error.message}` }
}
async function createSpace() {
  await action(async () => {
    const data = await api('/api/v1/spaces', 'POST', {
      type: adminSpace.type, floor: adminSpace.floor, zone: adminSpace.zone, number: adminSpace.number,
    })
    await loadResources()
    return data
  })
}
async function setSpaceStatus() {
  await action(async () => {
    const data = await api(`/api/v1/spaces/${adminSpace.spaceId}/service-status`, 'PATCH',
      { status: adminSpace.status })
    await loadResources()
    return data
  })
}
async function createReservation() {
  await action(async () => {
    reservation.value = await api('/api/v1/passes/reservations', 'POST', {
      plateNumber: reserve.plateNumber, spaceId: reserve.spaceId,
      startTime: iso(reserve.startTime), endTime: iso(reserve.endTime),
    })
    entry.reservationId = reservation.value.reservationId
    entry.plateNumber = reserve.plateNumber
    entry.spaceType = 'RESERVATION'
    return reservation.value
  })
}
async function payReservation(result) {
  await action(async () => {
    if (!reservation.value) throw new Error('请先创建预约')
    reservation.value = { ...reservation.value, ...await api(`/api/v1/passes/reservations/${reservation.value.reservationId}/pay`,
      'POST', { simulatedResult: result }) }
    return reservation.value
  })
}
async function cancelReservation() {
  await action(async () => {
    if (!reservation.value) throw new Error('请先创建预约')
    reservation.value = { ...reservation.value, ...await api(`/api/v1/passes/reservations/${reservation.value.reservationId}/cancel`,
      'POST', {}) }
    return reservation.value
  })
}
async function createPass() {
  await action(async () => {
    monthly.value = await api('/api/v1/passes/monthly-passes', 'POST',
      { plateNumber: pass.plateNumber, startTime: iso(pass.startTime) })
    monthlyLedger.value = await api(`/api/v1/passes/monthly-passes/${monthly.value.monthlyPassId}/ledger`)
    return monthly.value
  })
}
async function refreshPass() {
  await action(async () => {
    if (!monthly.value) throw new Error('请先创建月卡')
    const id = monthly.value.monthlyPassId
    monthly.value = await api(`/api/v1/passes/monthly-passes/${id}`)
    monthlyLedger.value = await api(`/api/v1/passes/monthly-passes/${id}/ledger`)
    return { monthly: monthly.value, ledger: monthlyLedger.value }
  })
}
async function enter() {
  await action(async () => {
    const data = await api('/api/v1/access/entries', 'POST', {
      plateNumber: entry.plateNumber, spaceType: entry.spaceType,
      reservationId: entry.reservationId || undefined, entryTime: iso(entry.entryTime),
    })
    await selectSession(data)
    await loadResources()
    return session.value
  })
}
async function locate() {
  return action(async () => {
    await selectSession(await api(`/api/v1/access/locate?plateNumber=${encodeURIComponent(search.plateNumber)}`))
    return session.value
  })
}
async function findFromOverview() {
  if (await locate()) page.value = 'entry'
}
async function selectSession(data) {
  // Clear the previous vehicle's receipt before loading a new authoritative snapshot.
  bill.value = null
  exitResult.value = null
  if (session.value?.parkingSessionId !== data.parkingSessionId) {
    charging.value = null
    charge.startTime = ''; charge.endTime = ''; charge.energyKwh = ''
    invoiceResult.value = null
  }
  session.value = data
  exit.sessionId = data.parkingSessionId
  exit.exitTime = data.exitTime ? localInput(new Date(data.exitTime)) : ''
  exit.exceptionType = data.exceptionType || 'NONE'
  exit.operator = data.operator || ''
  charge.parkingSessionId = data.parkingSessionId
  charge.chargerId = chargers.value.find(c => c.spaceId === data.spaceId)?.chargerId || ''
  search.plateNumber = data.plateNumber
  invoice.billId = data.billId || ''
  bill.value = data.billId ? await api(`/api/v1/billing/bills/${data.billId}`) : null
  exitResult.value = data.billId ? { parkingSessionId: data.parkingSessionId, status: data.status } : null
}
async function startCharge() {
  await action(async () => {
    if (!charge.startTime) charge.startTime = localInput(new Date())
    charging.value = await api('/api/v1/spaces/charging-sessions', 'POST', {
      chargerId: charge.chargerId, parkingSessionId: charge.parkingSessionId,
      startTime: iso(charge.startTime || new Date().toISOString()),
    })
    await loadResources()
    return charging.value
  })
}
async function finishCharge() {
  await action(async () => {
    if (!charging.value) throw new Error('请先开始充电')
    if (!charge.endTime) charge.endTime = localInput(new Date())
    charging.value = await api(
      `/api/v1/spaces/charging-sessions/${charging.value.chargingSessionId}/finish`, 'POST',
      { endTime: iso(charge.endTime || new Date().toISOString()), energyKwh: charge.energyKwh })
    await loadResources()
    return charging.value
  })
}
async function requestExit() {
  await action(async () => {
    if (!exit.exitTime) exit.exitTime = localInput(new Date())
    const data = await api(`/api/v1/access/${exit.sessionId}/exit-requests`, 'POST', {
      exitTime: iso(exit.exitTime || new Date().toISOString()),
      exceptionType: exit.exceptionType, operator: exit.operator || undefined,
    })
    bill.value = await api(`/api/v1/billing/bills/${data.billId}`)
    invoice.billId = data.billId
    exitResult.value = data
    if (session.value?.parkingSessionId === data.parkingSessionId) session.value.status = data.status
    return { exit: data, bill: bill.value }
  })
}
async function completeExit(result) {
  await action(async () => {
    const data = await api(`/api/v1/access/${exit.sessionId}/complete-exit`,
      'POST', { simulatedResult: result })
    exitResult.value = data
    if (session.value?.parkingSessionId === data.parkingSessionId) session.value.status = data.status
    await loadResources()
    if (bill.value) bill.value = await api(`/api/v1/billing/bills/${bill.value.billId}`)
    return data
  }, true)
}
async function applyInvoice() {
  await action(async () => {
    invoiceResult.value = await api(`/api/v1/billing/bills/${invoice.billId}/invoice-requests`,
      'POST', { invoiceTitle: invoice.invoiceTitle, taxNumber: invoice.taxNumber || undefined })
    return invoiceResult.value
  })
}
async function traffic() {
  await action(async () => {
    const params = new URLSearchParams({
      from: iso(analytics.from), to: iso(analytics.to), granularity: analytics.granularity,
    })
    report.value = await api(`/api/v1/analytics/traffic?${params}`)
    return report.value
  })
}
onMounted(async () => {
  const now = new Date()
  analytics.from = localInput(new Date(now.getTime() - 24 * 60 * 60 * 1000))
  analytics.to = localInput(now)
  await refreshOverview()
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="page = 'overview'"><span class="brand-mark"><AppIcon name="plane" /></span><div><strong>空港智停</strong><small>AERO / PARK</small></div></a>
      <p class="nav-caption">WORKSPACE / 业务工作台</p>
      <nav aria-label="业务导航"><button v-for="item in navigation" :key="item.id" :class="{ active: page === item.id }" :aria-current="page === item.id ? 'page' : undefined" @click="page = item.id"><AppIcon :name="item.icon" />{{ item.label }}<AppIcon v-if="page === item.id" class="nav-arrow" name="arrow" /></button></nav>
      <div class="sidebar-guide"><AppIcon name="plane" /><p>停好这一程<br>从容赴下一程</p><span>PARK. CHARGE. GO.</span></div>
      <div class="sidebar-footer"><span class="demo-dot"></span>本地课程演示<p>车牌识别、支付及发票为模拟</p><small>软件建模技术 · 第 14 题</small></div>
    </aside>
    <main class="workspace" :aria-busy="busy">
      <header class="topbar"><span class="breadcrumb">机场智慧停车与出行服务平台 <span class="topbar-separator">/</span> <strong>{{ currentPage.label }}</strong></span><details class="connection-menu"><summary><span class="demo-dot" :class="{ disconnected: connectedCount < services.length }"></span>服务连接 {{ connectedCount }}/{{ services.length }}</summary><div class="connection-list"><div class="service-row" v-for="service in services" :key="service.path"><span>{{ service.name }}</span><span class="service-state" :class="{ online: service.state === '已连接' }">{{ service.state }}</span></div><button class="text-button" @click="checkServices">重新检查连接</button></div></details></header>
      <div class="page">
        <header class="page-header"><div><p class="eyebrow">AERO / PARK <span>OPERATIONS</span></p><h1>{{ currentPage.label }}<span class="title-dot">.</span></h1><p class="subtitle">{{ currentPage.hint }}</p></div><div class="header-tools"><small class="updated">资源快照<br>{{ updatedAt || '尚未加载' }}</small><el-button :loading="busy" @click="action(refreshOverview)"><AppIcon name="refresh" />刷新数据</el-button></div></header>
        <el-alert v-if="resourceError" :title="resourceError" type="warning" show-icon :closable="false" />
        <el-alert v-if="lastAction && noticeType !== 'success'" :title="lastAction" :type="noticeType" show-icon closable @close="lastAction = ''" />

    <el-tabs v-model="page" class="business-pages">
      <el-tab-pane name="overview" label="运营总览">
        <section class="metric-grid" aria-label="资源统计">
          <article class="metric-card accent"><div class="metric-label"><span>可用车位</span><AppIcon name="parking" /></div><strong>{{ resourcesReady ? metrics.available : '—' }}<small>个</small></strong><p>{{ resourcesReady ? `全场 ${metrics.total} 个 · 停用 ${metrics.offline} 个` : '等待资源服务响应' }}</p></article>
          <article class="metric-card"><div class="metric-label"><span>在场占用率</span><AppIcon name="car" /></div><strong>{{ resourcesReady ? occupancy : '—' }}<small>%</small></strong><p>{{ resourcesReady ? `${metrics.occupied} 个车位正在使用 / 全部物理车位` : '等待资源服务响应' }}</p></article>
          <article class="metric-card"><div class="metric-label"><span>近 24 小时入场</span><AppIcon name="arrowUp" /></div><strong>{{ dashboardError ? '—' : dashboardReport?.totalEntries ?? '—' }}<small>辆</small></strong><p>基于实际入场记录</p></article>
          <article class="metric-card"><div class="metric-label"><span>充电桩使用</span><AppIcon name="bolt" /></div><strong>{{ resourcesReady ? chargers.filter(c => c.status === 'IN_USE').length : '—' }}<small>/ {{ resourcesReady ? chargers.length : '—' }}</small></strong><p>当前正在服务的充电桩</p></article>
        </section>
        <div class="overview-grid">
          <section class="panel map-panel"><div class="panel-heading"><div><span class="section-kicker">01 / PARKING MAP</span><h2>停车分区</h2></div><button class="text-button" @click="page = 'resources'">管理资源<AppIcon name="arrowUp" /></button></div><SpaceMap v-if="resourcesReady" :spaces="spaces" @select="space => adminSpace.spaceId = space.spaceId" /><p v-else class="empty-state">资源暂未加载</p></section>
          <aside class="workbench-rail">
            <section class="arrival-card"><div class="arrival-top"><span>READY FOR ARRIVAL</span><AppIcon name="plane" /></div><h2>下一辆车<br>从这里开始。</h2><p>识别车牌 · 分配车位 · 登记入场</p><button class="primary-link" @click="page = 'entry'">登记车辆入场<AppIcon name="arrow" /></button></section>
            <section class="panel quick-find"><div class="panel-heading"><h2>车停在哪里？</h2><AppIcon name="pin" /></div><p class="muted">输入车牌，查询在场位置。</p><el-input v-model="search.plateNumber" aria-label="总览寻车车牌" placeholder="输入车牌号" @keyup.enter="findFromOverview" /><el-button :loading="busy" @click="findFromOverview"><AppIcon name="search" />查找车辆</el-button></section>
            <button class="owner-link" @click="page = 'passes'"><span class="owner-icon"><AppIcon name="ticket" /></span><span><strong>提前安排下一程</strong><small>车位预约 / 月卡权益</small></span><AppIcon name="arrowUp" /></button>
          </aside>
        </div>
        <div class="overview-bottom"><section class="panel chart-panel"><div class="panel-heading"><div><span class="section-kicker">02 / TRAFFIC</span><h2>近 24 小时车流</h2></div><button class="text-button" @click="page = 'analytics'">查看报表<AppIcon name="arrowUp" /></button></div><el-alert v-if="dashboardError" :title="dashboardError" type="warning" :closable="false" /><TrafficChart v-else :report="dashboardReport" /></section><section class="panel"><div class="panel-heading"><div><span class="section-kicker">03 / AVAILABILITY</span><h2>分类余量</h2></div><span class="muted">可用 / 总量</span></div><div class="resource-lines"><div v-for="(title, type) in types" :key="type"><span><AppIcon :name="typeIcon(type)" />{{ title }}</span><strong>{{ resourcesReady ? spaces.filter(s => s.type === type && s.status === 'AVAILABLE').length : '—' }}<small> / {{ resourcesReady ? spaces.filter(s => s.type === type).length : '—' }}</small></strong></div></div><p class="map-caption">未来预约保留由预约服务按时间窗核验。</p></section></div>
      </el-tab-pane>
      <el-tab-pane name="resources" label="车位资源">
        <h2>车位分区与状态</h2><SpaceMap :spaces="spaces" :highlight="adminSpace.spaceId" @select="space => adminSpace.spaceId = space.spaceId" />
        <div class="toolbar"><el-button @click="action(loadResources)">刷新车位</el-button></div>
        <el-table :data="spaces" stripe max-height="320">
          <el-table-column prop="number" label="编号" />
          <el-table-column prop="floor" label="楼层" />
          <el-table-column prop="zone" label="区域" />
          <el-table-column label="类型"><template #default="{ row }">{{ types[row.type] }}</template></el-table-column>
          <el-table-column label="当前状态"><template #default="{ row }"><el-tag :type="row.status === 'AVAILABLE' ? 'success' : row.status === 'OCCUPIED' ? 'warning' : 'info'">{{ label(row.status) }}</el-tag></template></el-table-column>
        </el-table>
        <h3>维护车位</h3>
        <div class="form-grid">
          <el-select v-model="adminSpace.type" aria-label="新增车位类型"><el-option v-for="(title, type) in types" :key="type" :label="title" :value="type" /></el-select>
          <el-input v-model="adminSpace.floor" placeholder="楼层，例如 B1" />
          <el-input v-model="adminSpace.zone" placeholder="区域，例如 A" />
          <el-input v-model="adminSpace.number" placeholder="唯一编号，例如 A-101" />
          <el-button type="primary" :loading="busy" @click="createSpace">新增车位</el-button>
        </div>
        <div class="form-grid subform">
          <el-select v-model="adminSpace.spaceId" placeholder="选择车位" filterable><el-option v-for="space in spaces" :key="space.spaceId" :label="space.number" :value="space.spaceId" /></el-select>
          <el-select v-model="adminSpace.status"><el-option label="停用" value="OUT_OF_SERVICE" /><el-option label="恢复可用" value="AVAILABLE" /></el-select>
          <el-button :loading="busy" @click="setSpaceStatus">更新状态</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane name="passes" label="预约与月卡">
        <h3>预约指定车位</h3>
        <div class="form-grid">
          <el-input v-model="reserve.plateNumber" placeholder="车牌号" />
          <el-select v-model="reserve.spaceId" placeholder="预约位"><el-option v-for="space in spaces.filter(s => s.type === 'RESERVATION')" :key="space.spaceId" :label="space.number" :value="space.spaceId" /></el-select>
          <label>开始 <input v-model="reserve.startTime" type="datetime-local" step="0.001"></label>
          <label>结束 <input v-model="reserve.endTime" type="datetime-local" step="0.001"></label>
          <el-button type="primary" :loading="busy" @click="createReservation">创建预约</el-button>
        </div>
        <div v-if="reservation" class="result-card"><el-tag>{{ label(reservation.status) }}</el-tag><h3>预约凭证</h3><p>预付金额 {{ money(reservation.prepaidCents) }}</p><code>{{ reservation.reservationId }}</code><p class="muted">支付成功后可使用预约凭证登记入场。</p><el-button v-if="reservation.status === 'CONFIRMED'" @click="page = 'entry'">使用预约入场 →</el-button></div>
        <div class="toolbar">
          <el-button :loading="busy" @click="payReservation('SUCCESS')">模拟预付成功</el-button>
          <el-button :loading="busy" @click="payReservation('FAILURE')">模拟预付失败</el-button>
          <el-button :loading="busy" @click="cancelReservation">取消预约</el-button>
        </div>
        <h3>开通月卡</h3>
        <div class="form-grid">
          <el-input v-model="pass.plateNumber" placeholder="绑定车牌" />
          <label>生效 <input v-model="pass.startTime" type="datetime-local" step="0.001"></label>
          <el-button :loading="busy" @click="createPass">创建月卡</el-button>
        </div>
        <p v-if="monthly">月卡 {{ monthly.monthlyPassId }} · 余额 {{ money(monthly.balanceCents) }}</p>
        <el-button v-if="monthly" :loading="busy" @click="refreshPass">刷新余额与扣费流水</el-button>
        <el-table v-if="monthlyLedger.length" :data="monthlyLedger" stripe max-height="220">
          <el-table-column prop="kind" label="流水类型" />
          <el-table-column label="金额"><template #default="{ row }">{{ money(row.amountCents) }}</template></el-table-column>
          <el-table-column label="剩余余额"><template #default="{ row }">{{ money(row.balanceAfterCents) }}</template></el-table-column>
          <el-table-column prop="parkingSessionId" label="停车记录" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane name="entry" label="入场与寻车">
        <div class="entry-layout">
          <div class="entry-controls">
            <section class="panel entry-form"><div class="panel-heading"><div><span class="section-kicker">01 / CHECK IN</span><h2>车辆入场登记</h2></div><span class="small-badge">模拟识别</span></div>
              <label class="field-label" for="entry-plate">车牌号码</label><el-input id="entry-plate" v-model="entry.plateNumber" class="plate-input" placeholder="请输入车牌号" maxlength="12" @keyup.enter="enter" />
              <span class="field-label">车位类型</span><div class="type-picker"><button v-for="(title, type) in types" :key="type" :class="{ selected: entry.spaceType === type }" :aria-pressed="entry.spaceType === type" @click="entry.spaceType = type"><AppIcon :name="typeIcon(type)" /><span>{{ title }}</span></button></div>
              <label v-if="entry.spaceType === 'RESERVATION'" class="stacked-field">预约凭证<el-input v-model="entry.reservationId" placeholder="已确认预约的 ID" /></label>
              <details class="advanced-options"><summary>演示时间设置</summary><label class="stacked-field">入场时间（留空使用当前时刻）<input v-model="entry.entryTime" type="datetime-local" step="0.001"></label></details>
              <el-button class="full-button" type="primary" :loading="busy" :disabled="!entry.plateNumber.trim()" @click="enter">登记入场<AppIcon name="arrow" /></el-button><p class="form-note">可用车位与预约资格由服务端核验。</p>
            </section>
            <section class="panel search-panel"><div class="panel-heading"><div><span class="section-kicker">02 / FIND MY CAR</span><h2>反向寻车</h2></div><AppIcon name="pin" /></div><label class="field-label" for="locate-plate">在场车辆车牌</label><div class="search-row"><el-input id="locate-plate" v-model="search.plateNumber" placeholder="按车牌反向寻车" @keyup.enter="locate" /><el-button :loading="busy" :disabled="!search.plateNumber.trim()" @click="locate"><AppIcon name="search" />查找</el-button></div></section>
          </div>
          <section class="panel location-panel"><div class="panel-heading"><div><span class="section-kicker">PARKING POSITION</span><h2>{{ session?.status === 'CLOSED' ? '本次停车已结束' : '车辆位置' }}</h2></div><el-tag v-if="session" :type="session.status === 'CLOSED' ? 'info' : 'success'">{{ label(session.status) }}</el-tag></div>
            <template v-if="session"><div class="location-hero"><span class="license-plate">{{ session.plateNumber }}</span><div class="location-number"><span class="location-p">P</span><strong>{{ session.spaceNumber }}</strong><AppIcon name="car" /></div><div class="location-meta"><span><AppIcon name="layers" />{{ session.floor }} 层</span><span><AppIcon name="pin" />{{ session.zone }} 区</span></div></div><div class="location-time"><AppIcon name="clock" /><span>入场 {{ dateTime(session.entryTime) }}</span></div><SpaceMap :spaces="spaces" :highlight="session.status === 'CLOSED' ? undefined : session.spaceId" /><div class="location-actions"><button class="text-button" v-if="session.status !== 'CLOSED'" @click="page = 'charging'">充电服务<AppIcon name="arrowUp" /></button><el-button v-if="session.status !== 'CLOSED'" type="primary" @click="page = 'billing'">前往出场结算<AppIcon name="arrow" /></el-button></div><details class="advanced-options"><summary>停车记录详情</summary><code>{{ session.parkingSessionId }}</code><p v-if="session.status === 'CLOSED'" class="muted">上方为本次历史停车位置，车辆已出场。</p></details></template>
            <div v-else class="location-empty"><div class="empty-car"><AppIcon name="car" /><span>P</span></div><h3>先找到车，再开始下一程</h3><p>登记入场或输入在场车牌<br>这里将显示楼层、区域与车位编号。</p></div>
          </section>
        </div>
      </el-tab-pane>

      <el-tab-pane name="charging" label="充电">
        <h2>充电工作台</h2><p class="muted">车辆须停在对应充电车位。先结束充电，再生成停车结算账单。</p>
        <div class="charger-grid"><article v-for="item in chargers" :key="item.chargerId" class="charger-card" :class="{ 'in-use': item.status === 'IN_USE' }"><AppIcon class="charger-icon" name="bolt" /><strong>{{ spaces.find(s => s.spaceId === item.spaceId)?.number || '充电车位' }}</strong><el-tag :type="item.status === 'AVAILABLE' ? 'success' : 'warning'">{{ label(item.status) }}</el-tag><el-button size="small" @click="charge.chargerId = item.chargerId">选择此桩</el-button></article></div>
        <div class="form-grid">
          <el-select v-model="charge.chargerId" placeholder="充电桩"><el-option v-for="item in chargers" :key="item.chargerId" :label="`${spaces.find(s => s.spaceId === item.spaceId)?.number || item.chargerId.slice(0,8)} · ${label(item.status)}`" :value="item.chargerId" /></el-select>
          <el-input v-model="charge.parkingSessionId" placeholder="停车记录 ID" />
          <label>开始时间（可留空）<input v-model="charge.startTime" type="datetime-local" step="0.001"></label>
          <el-button type="primary" :loading="busy" @click="startCharge">开始充电</el-button>
        </div>
        <div class="form-grid subform">
          <label>结束时间（可留空）<input v-model="charge.endTime" type="datetime-local" step="0.001"></label>
          <el-input v-model="charge.energyKwh" type="number" min="0.001" step="0.001" placeholder="电量 kWh" />
          <el-button :loading="busy" @click="finishCharge">结束充电</el-button>
        </div>
        <div v-if="charging" class="result-card"><el-tag>{{ label(charging.status) }}</el-tag><h3>本次充电 {{ charging.energyKwh ?? '—' }} kWh · {{ money(charging.chargingCents) }}</h3><p>{{ dateTime(charging.startTime) }} → {{ dateTime(charging.endTime) }}</p><code>{{ charging.chargingSessionId }}</code><p class="muted">充电费用由后端计算，出场时并入停车账单。</p></div>
      </el-tab-pane>

      <el-tab-pane name="billing" label="出场结算">
        <div class="checkout-steps" aria-label="结算进度"><div v-for="(title, index) in ['确认车辆', '核对账单', '支付完成', '车位释放']" :key="title" :class="{ done: settlementStep > index, current: settlementStep === index }"><span><AppIcon v-if="settlementStep > index" name="check" /><template v-else>{{ index + 1 }}</template></span><strong>{{ title }}</strong></div></div>
        <div class="checkout-layout">
          <section class="receipt"><div class="receipt-brand"><AppIcon name="plane" /><span>AERO / PARK<small>停车结算凭据 · 模拟支付</small></span><el-tag v-if="bill" :type="bill.status === 'PAID' ? 'success' : 'warning'">{{ label(bill.status) }}</el-tag></div>
            <template v-if="session"><div class="receipt-vehicle"><span class="license-plate">{{ session.plateNumber }}</span><span>{{ session.floor }} · {{ session.zone }}区 · {{ session.spaceNumber }}</span></div><div class="receipt-times"><div><span>入场时间</span><strong>{{ dateTime(session.entryTime) }}</strong></div><div><span>结算时间</span><strong>{{ dateTime(exit.exitTime) }}</strong></div></div></template>
            <template v-if="bill"><div class="receipt-items"><div v-for="(item, index) in bill.feeItems" :key="index" class="receipt-item" :class="{ deduction: ['DISCOUNT', 'PREPAID_APPLIED'].includes(item.name) }"><div><strong>{{ feeNames[item.name] || item.name }}</strong><p>{{ item.detail }}</p></div><span>{{ ['DISCOUNT', 'PREPAID_APPLIED'].includes(item.name) ? '− ' : '' }}{{ money(item.amountCents) }}</span></div></div><p v-if="bill.prepaidRefundCents" class="refund-note">应退预付差额 {{ money(bill.prepaidRefundCents) }}，由预约服务处理；不计入本次应付。</p><div class="receipt-total"><span>本次应付<small>人民币 CNY</small></span><strong>{{ money(bill.amountDueCents) }}</strong></div><details class="advanced-options"><summary>查看账单与规则版本</summary><p>账单 <code>{{ bill.billId }}</code></p><p>费率版本 <code>{{ bill.rateVersion }}</code></p><p class="muted">停车费、优惠、预付抵扣、充电及异常费用均由后端生成快照。</p></details><div class="receipt-bottom">THANK YOU & HAVE A GOOD TRIP<AppIcon name="plane" /></div></template>
            <div v-else class="receipt-empty"><AppIcon name="ticket" /><h3>费用清楚，出场顺畅</h3><p>确认在场车辆后生成账单<br>停车、充电与优惠将在此逐项列明。</p></div>
          </section>
          <div class="checkout-controls"><section class="panel"><div class="panel-heading"><div><span class="section-kicker">CHECKOUT DESK</span><h2>出场工作台</h2></div><AppIcon name="wallet" /></div><label class="field-label" for="checkout-plate">查询在场车辆</label><div class="search-row"><el-input id="checkout-plate" v-model="search.plateNumber" placeholder="输入待结算车牌" @keyup.enter="locate" /><el-button :loading="busy" :disabled="!search.plateNumber.trim()" @click="locate">查询</el-button></div><div v-if="session" class="selected-vehicle"><AppIcon name="car" /><strong>{{ session.plateNumber }}</strong><span>{{ label(session.status) }}</span></div>
            <template v-if="!bill"><label class="stacked-field">出场类型<el-select v-model="exit.exceptionType" aria-label="出场类型"><el-option label="正常出场" value="NONE" /><el-option label="丢卡处理" value="LOST_CARD" /></el-select></label><label v-if="exit.exceptionType === 'LOST_CARD'" class="stacked-field">异常处理人<el-input v-model="exit.operator" placeholder="请输入处理人" /></label><details class="advanced-options"><summary>演示时间与停车记录</summary><label class="stacked-field">出场时间（留空使用当前时刻）<input v-model="exit.exitTime" type="datetime-local" step="0.001"></label><label class="stacked-field">停车记录 ID<el-input v-model="exit.sessionId" :disabled="!!session" placeholder="可使用接口返回的记录 ID" /></label></details><el-button class="full-button" type="primary" :loading="busy" :disabled="!exit.sessionId" @click="requestExit">生成结算账单<AppIcon name="arrow" /></el-button></template>
            <template v-else><div class="payment-summary"><span>{{ bill.status === 'PAID' ? '已支付金额' : '待付金额' }}</span><strong>{{ money(bill.amountDueCents) }}</strong></div><el-alert v-if="exitResult" :title="`停车记录：${label(exitResult.status)}${exitResult.spaceReleased === true ? '，车位已释放' : ''}`" :type="exitResult.status === 'CLOSED' ? 'success' : 'warning'" :closable="false" show-icon /><el-button class="full-button" type="primary" :loading="busy" :disabled="session?.status === 'CLOSED' || exitResult?.status === 'CLOSED'" @click="completeExit('SUCCESS')">{{ (session?.status === 'CLOSED' || exitResult?.status === 'CLOSED') ? '出场已完成' : bill.status === 'PAID' ? '重试释放并完成出场' : '模拟支付并完成出场' }}<AppIcon name="arrow" /></el-button><details v-if="bill.status !== 'PAID'" class="advanced-options"><summary>支付失败场景演示</summary><p class="muted">模拟支付失败会保留账单，供再次支付。</p><el-button :loading="busy" @click="completeExit('FAILURE')">模拟支付失败</el-button></details></template><p class="form-note">先确认支付，再释放车位；释放失败可重试。</p>
          </section>
          <section class="panel invoice-panel"><div class="panel-heading"><h2>电子发票</h2><span class="small-badge">模拟申请</span></div><p v-if="bill?.status !== 'PAID'" class="muted">账单支付完成后，可申请电子发票。</p><template v-else><label class="stacked-field">发票抬头<el-input v-model="invoice.invoiceTitle" placeholder="个人姓名或企业名称" /></label><label class="stacked-field">税号（可选）<el-input v-model="invoice.taxNumber" placeholder="企业纳税人识别号" /></label><el-button class="full-button" :loading="busy" :disabled="!invoice.invoiceTitle.trim()" @click="applyInvoice">提交发票申请</el-button></template><div v-if="invoiceResult" class="invoice-success"><AppIcon name="check" /><div><strong>申请已受理（模拟）</strong><code>{{ invoiceResult.invoiceRequestId }}</code></div></div></section></div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="analytics" label="运营报表">
        <h2>车流量与高峰统计</h2><p class="muted">按入场时刻统计，时间区间左闭右开；生成报表会保存可复核快照。</p>
        <div class="form-grid">
          <label>开始 <input v-model="analytics.from" type="datetime-local" step="0.001"></label>
          <label>结束 <input v-model="analytics.to" type="datetime-local" step="0.001"></label>
          <el-select v-model="analytics.granularity"><el-option label="按小时" value="HOUR" /><el-option label="按天" value="DAY" /></el-select>
          <el-button type="primary" :loading="busy" @click="traffic">生成车流报表</el-button>
        </div>
        <TrafficChart :report="report" />
        <p v-if="report" class="muted">报表编号 {{ report.reportId }} · 范围 {{ dateTime(report.from) }} 至 {{ dateTime(report.to) }}</p>
        <el-table v-if="report" :data="report.buckets" stripe max-height="300">
          <el-table-column label="时段开始（上海时间）"><template #default="{ row }">{{ dateTime(row.start) }}</template></el-table-column><el-table-column prop="count" label="入场数" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <details class="result"><summary>联调详情 · 最近一次接口结果</summary><pre>{{ output }}</pre></details>
    <footer class="page-footer">机场智慧停车与出行服务平台 <span>软件工程 24201718 姜苏豪 · 【AI-辅助】课程演示</span></footer>
    </div>
  </main>
  </div>
</template>
