<script setup>
import AppIcon from '../components/AppIcon.vue'

// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const {
  label,
  busy,
  spaces,
  chargers,
  charging,
  charge,
  money,
  dateTime,
  startCharge,
  finishCharge,
} = props.workspace
</script>

<template>
  <h2>充电工作台</h2>
  <p class="muted">车辆须停在对应充电车位。先结束充电，再生成停车结算账单。</p>
  <div class="charger-grid">
    <article
      v-for="item in chargers"
      :key="item.chargerId"
      class="charger-card"
      :class="{ 'in-use': item.status === 'IN_USE' }"
    >
      <AppIcon class="charger-icon" name="bolt" /><strong>{{
        spaces.find((s) => s.spaceId === item.spaceId)?.number || '充电车位'
      }}</strong
      ><el-tag :type="item.status === 'AVAILABLE' ? 'success' : 'warning'">{{
        label(item.status)
      }}</el-tag
      ><el-button size="small" @click="charge.chargerId = item.chargerId">选择此桩</el-button>
    </article>
  </div>
  <div class="form-grid">
    <el-select v-model="charge.chargerId" placeholder="充电桩"
      ><el-option
        v-for="item in chargers"
        :key="item.chargerId"
        :label="`${spaces.find((s) => s.spaceId === item.spaceId)?.number || item.chargerId.slice(0, 8)} · ${label(item.status)}`"
        :value="item.chargerId"
    /></el-select>
    <el-input v-model="charge.parkingSessionId" placeholder="停车记录 ID" />
    <label
      >开始时间（可留空）<input v-model="charge.startTime" type="datetime-local" step="0.001"
    /></label>
    <el-button type="primary" :loading="busy" @click="startCharge">开始充电</el-button>
  </div>
  <div class="form-grid subform">
    <label
      >结束时间（可留空）<input v-model="charge.endTime" type="datetime-local" step="0.001"
    /></label>
    <el-input
      v-model="charge.energyKwh"
      type="number"
      min="0.001"
      step="0.001"
      placeholder="电量 kWh"
    />
    <el-button :loading="busy" @click="finishCharge">结束充电</el-button>
  </div>
  <div v-if="charging" class="result-card">
    <el-tag>{{ label(charging.status) }}</el-tag>
    <h3>本次充电 {{ charging.energyKwh ?? '—' }} kWh · {{ money(charging.chargingCents) }}</h3>
    <p>{{ dateTime(charging.startTime) }} → {{ dateTime(charging.endTime) }}</p>
    <code>{{ charging.chargingSessionId }}</code>
    <p class="muted">充电费用由后端计算，出场时并入停车账单。</p>
  </div>
</template>
