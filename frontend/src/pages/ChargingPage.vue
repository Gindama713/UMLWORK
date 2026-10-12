<script setup>
import AppIcon from '../components/AppIcon.vue'

const props = defineProps({ workspace: { type: Object, required: true } })
const { label, busy, spaces, chargers, charging, charge, money, dateTime, startCharge, finishCharge } = props.workspace
const spaceFor = charger => spaces.value.find(space => space.spaceId === charger.spaceId)
</script>

<template>
  <div class="service-intro charge-intro">
    <div><span class="section-kicker">ELECTRIC MOBILITY / 充电服务</span><h2>停好车，也为下一程充好电。</h2><p>车已停入对应充电位后，选桩、开始充电、记录用量。</p></div>
    <div class="charge-count"><strong>{{ chargers.filter(item => item.status === 'AVAILABLE').length }}</strong><span>/ {{ chargers.length }} 可用充电桩</span></div>
  </div>

  <section class="service-panel">
    <div class="service-panel-heading"><span class="service-panel-icon"><AppIcon name="bolt" /></span><div><span class="section-kicker">01 / SELECT A CHARGER</span><h3>选择充电桩</h3></div></div>
    <div v-if="chargers.length" class="charger-grid">
      <button v-for="item in chargers" :key="item.chargerId" type="button" class="charger-card" :class="{ 'in-use': item.status === 'IN_USE', 'is-selected': charge.chargerId === item.chargerId }" :aria-pressed="charge.chargerId === item.chargerId" :aria-label="`${spaceFor(item)?.number || '充电车位'}，${label(item.status)}${charge.chargerId === item.chargerId ? '，已选择' : ''}`" @click="charge.chargerId = item.chargerId">
        <span class="charger-card-top"><AppIcon name="bolt" /><span class="charger-status" :class="{ active: item.status === 'IN_USE' }">{{ label(item.status) }}</span></span>
        <strong>{{ spaceFor(item)?.number || '充电车位' }}</strong>
        <small>{{ spaceFor(item)?.floor || '—' }} · {{ spaceFor(item)?.zone || '—' }} 区</small>
        <span class="charger-pick">{{ charge.chargerId === item.chargerId ? '当前已选择' : '点击选择' }} →</span>
      </button>
    </div>
    <p v-else class="empty-state">尚无充电桩，请先在资源管理中新增充电车位。</p>
  </section>

  <div class="service-columns charge-workflow">
    <section class="service-panel">
      <div class="service-panel-heading"><span class="service-panel-icon"><AppIcon name="car" /></span><div><span class="section-kicker">02 / START</span><h3>开始充电</h3></div></div>
      <p class="service-panel-note">停车记录必须对应所选充电位；服务端会再次核验。</p>
      <div class="service-form">
        <label>停车记录 ID<el-input v-model="charge.parkingSessionId" aria-label="充电停车记录 ID" placeholder="入场登记后自动带入" /></label>
        <label>开始时间（可留空）<input v-model="charge.startTime" type="datetime-local" step="0.001" /></label>
      </div>
      <el-button class="service-primary" type="primary" :loading="busy" :disabled="!charge.chargerId || !charge.parkingSessionId || charging?.status === 'ACTIVE'" @click="startCharge">开始充电</el-button>
    </section>
    <section class="service-panel">
      <div class="service-panel-heading"><span class="service-panel-icon"><AppIcon name="wallet" /></span><div><span class="section-kicker">03 / FINISH</span><h3>结束并记录用量</h3></div></div>
      <p class="service-panel-note">先结束充电，再申请出场结算；金额由车位资源服务计算。</p>
      <div class="service-form">
        <label>结束时间（可留空）<input v-model="charge.endTime" type="datetime-local" step="0.001" /></label>
        <label>充电量（kWh）<el-input v-model="charge.energyKwh" aria-label="充电量 kWh" type="number" min="0.001" step="0.001" placeholder="例如 2.000" /></label>
      </div>
      <el-button class="service-primary" :loading="busy" :disabled="charging?.status !== 'ACTIVE' || !charge.energyKwh" @click="finishCharge">结束充电</el-button>
    </section>
  </div>

  <section v-if="charging" class="service-panel charge-result">
    <div class="service-panel-heading"><span class="service-panel-icon"><AppIcon name="bolt" /></span><div><span class="section-kicker">CHARGING SESSION</span><h3>本次充电</h3></div><el-tag :type="charging.status === 'ACTIVE' ? 'warning' : 'success'">{{ label(charging.status) }}</el-tag></div>
    <div class="charge-result-main"><strong>{{ charging.energyKwh ?? '—' }} <small>kWh</small></strong><strong>{{ money(charging.chargingCents) }}</strong></div>
    <p class="muted">{{ dateTime(charging.startTime) }} → {{ dateTime(charging.endTime) }}</p>
    <p class="service-panel-note">充电费用在出场时并入同一张停车结算账单。</p>
    <details class="service-demo-option"><summary>查看充电会话 ID</summary><code>{{ charging.chargingSessionId }}</code></details>
  </section>
</template>
