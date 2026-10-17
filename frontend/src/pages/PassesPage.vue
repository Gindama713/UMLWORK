<script setup>
import AppIcon from '../components/AppIcon.vue'

const props = defineProps({ workspace: { type: Object, required: true } })
const {
  page, label, busy, spaces, reservation, reservationAvailability, monthly, monthlyLedger, reserve, pass,
  money, dateTime, createReservation, payReservation, refreshReservation, cancelReservation,
  loadReservationAvailability, createPass, refreshPass,
} = props.workspace

function windowStyle(window) {
  const start = Date.parse(reservationAvailability.value.from)
  const end = Date.parse(reservationAvailability.value.to)
  const left = Math.max(0, (Date.parse(window.startTime) - start) / (end - start)) * 100
  const right = Math.min(1, (Date.parse(window.endTime) - start) / (end - start)) * 100
  return { left: `${left}%`, width: `${Math.max(0, right - left)}%` }
}
</script>

<template>
  <div class="service-intro pass-intro">
    <div><span class="section-kicker">PARKING PRIVILEGES / 车主权益</span><h2>提前安排，抵达即停。</h2><p>预约保留未来时段；月卡权益在结算时由服务端核验。</p></div>
    <AppIcon name="ticket" />
  </div>

  <div class="service-columns">
    <section class="service-panel" aria-label="车位预约">
      <div class="service-panel-heading"><span class="service-panel-icon"><AppIcon name="ticket" /></span><div><span class="section-kicker">01 / RESERVATION</span><h3>预约指定车位</h3></div></div>
      <p class="service-panel-note">选择预约车位及左闭右开的使用时段，预付后才可凭证入场。</p>
      <div class="service-form">
        <label>车牌号码<el-input v-model="reserve.plateNumber" aria-label="预约车牌号码" placeholder="例如 苏A12345" /></label>
        <label>预约车位<el-select v-model="reserve.spaceId" aria-label="预约车位" placeholder="选择车位"><el-option v-for="space in spaces.filter(s => s.type === 'RESERVATION')" :key="space.spaceId" :label="`${space.floor} · ${space.zone}区 · ${space.number}`" :value="space.spaceId" /></el-select></label>
        <label>开始时间<input v-model="reserve.startTime" type="datetime-local" step="0.001" /></label>
        <label>结束时间<input v-model="reserve.endTime" type="datetime-local" step="0.001" /></label>
      </div>
      <div class="service-actions">
        <el-button class="service-primary reservation-create" type="primary" :loading="busy" @click="createReservation">创建预约</el-button>
        <el-button :loading="busy" @click="loadReservationAvailability">查看时段空档</el-button>
      </div>

      <div v-if="reservation" class="service-result">
        <div class="service-result-top"><span>预约凭证</span><el-tag :type="reservation.status === 'CONFIRMED' ? 'success' : reservation.status === 'PENDING_PAYMENT' ? 'warning' : 'info'">{{ label(reservation.status) }}</el-tag></div>
        <strong>{{ money(reservation.prepaidCents) }}</strong><small>预约预付 · 由预约服务记录</small>
        <code>{{ reservation.reservationId }}</code>
        <div class="service-actions">
          <el-button v-if="reservation.status === 'PENDING_PAYMENT'" type="primary" :loading="busy" @click="payReservation('SUCCESS')">模拟预付成功</el-button>
          <el-button v-if="reservation.status === 'CONFIRMED'" @click="page = 'entry'">凭证入场 →</el-button>
          <el-button :loading="busy" @click="refreshReservation">刷新状态</el-button>
          <el-button v-if="['PENDING_PAYMENT', 'CONFIRMED'].includes(reservation.status)" :loading="busy" @click="cancelReservation">取消预约</el-button>
        </div>
        <details v-if="reservation.status === 'PENDING_PAYMENT'" class="service-demo-option"><summary>演示预付失败</summary><el-button :loading="busy" @click="payReservation('FAILURE')">模拟预付失败</el-button></details>
      </div>
      <p v-else class="service-placeholder">创建预约后，预付金额与凭证会显示在这里。</p>
    </section>

    <section class="service-panel" aria-label="月卡权益">
      <div class="service-panel-heading"><span class="service-panel-icon"><AppIcon name="wallet" /></span><div><span class="section-kicker">02 / MONTHLY PASS</span><h3>开通月卡</h3></div></div>
      <p class="service-panel-note">绑定车牌与生效时间；使用范围、余额和扣费由月卡服务管理。</p>
      <div class="service-form">
        <label>绑定车牌<el-input v-model="pass.plateNumber" aria-label="月卡绑定车牌" placeholder="例如 苏A12345" /></label>
        <label>生效时间<input v-model="pass.startTime" type="datetime-local" step="0.001" /></label>
      </div>
      <el-button class="service-primary" type="primary" :loading="busy" @click="createPass">创建月卡</el-button>

      <div v-if="monthly" class="service-result monthly-result">
        <div class="service-result-top"><span>月卡账户</span><el-tag type="success">{{ label(monthly.status) }}</el-tag></div>
        <strong>{{ money(monthly.balanceCents) }}</strong><small>当前预存余额</small>
        <div class="service-meta"><span>绑定车牌</span><b>{{ monthly.plateNumber }}</b></div>
        <div class="service-meta"><span>有效期至</span><b>{{ dateTime(monthly.endTime) }}</b></div>
        <code>{{ monthly.monthlyPassId }}</code>
        <el-button :loading="busy" @click="refreshPass">刷新余额与流水</el-button>
      </div>
      <p v-else class="service-placeholder">开卡后，余额和有效期会显示在这里。</p>
    </section>
  </div>

  <section v-if="reservationAvailability" class="service-panel reservation-timeline" aria-label="预约车位时间窗">
    <div class="service-panel-heading"><span class="service-panel-icon"><AppIcon name="ticket" /></span><div><span class="section-kicker">RESERVATION TIMELINE</span><h3>预约车位时间窗</h3></div></div>
    <p class="service-panel-note">{{ dateTime(reservationAvailability.from) }} 至 {{ dateTime(reservationAvailability.to) }} · 浅色为空档，色块为已有预约。此处是查询快照；创建、支付或取消后请刷新，最终以创建预约时的冲突检查为准。</p>
    <div class="reservation-legend"><span>空档</span><span>待预付</span><span>已确认</span><span>已核销</span></div>
    <div class="reservation-timeline-list">
      <div v-for="space in reservationAvailability.spaces" :key="space.spaceId" class="reservation-timeline-row">
        <div class="reservation-space-name"><strong>{{ space.number }}</strong><small>{{ space.floor }} · {{ space.zone }}区 · {{ label(space.spaceStatus) }}</small></div>
        <div class="reservation-track-wrap">
          <div class="reservation-track" :class="{ 'is-offline': space.spaceStatus === 'OUT_OF_SERVICE' }" role="img" :aria-label="`${space.number}：${space.spaceStatus === 'OUT_OF_SERVICE' ? '停用' : space.windows.length ? `${space.windows.length} 个已占时段` : '查询时段可约'}`">
            <span v-for="(window, index) in space.windows" :key="index" class="reservation-block" :class="window.status.toLowerCase()" :style="windowStyle(window)" :title="`${dateTime(window.startTime)} 至 ${dateTime(window.endTime)} · ${label(window.status)}`" />
          </div>
          <small v-for="(window, index) in space.windows" :key="index" class="reservation-window-label">{{ dateTime(window.startTime) }}–{{ dateTime(window.endTime) }} · {{ label(window.status) }}</small>
        </div>
        <span class="reservation-space-state" :class="{ available: space.available }">{{ space.spaceStatus === 'OUT_OF_SERVICE' ? '停用' : space.available ? '时段可约' : '已有预约' }}</span>
        <button v-if="space.available" class="reservation-pick" type="button" @click="reserve.spaceId = space.spaceId">选此车位</button>
      </div>
      <p v-if="!reservationAvailability.spaces.length" class="service-placeholder">当前没有预约车位，请先在车位资源页配置。</p>
    </div>
    <p class="reservation-privacy">仅展示时段与车位状态，不展示其他车主的车牌或预约凭证；当前被占用的车位仍可预约未来时段。</p>
  </section>

  <section v-if="monthlyLedger.length" class="service-panel service-ledger">
    <div class="service-panel-heading"><span class="service-panel-icon"><AppIcon name="chart" /></span><div><span class="section-kicker">ACCOUNT ACTIVITY</span><h3>月卡扣费流水</h3></div></div>
    <el-table :data="monthlyLedger" stripe max-height="260">
      <el-table-column label="流水类型"><template #default="{ row }">{{ row.kind === 'INITIAL_CREDIT' ? '开卡预存' : row.kind === 'DEBIT' ? '停车扣款' : row.kind }}</template></el-table-column>
      <el-table-column label="金额"><template #default="{ row }">{{ money(row.amountCents) }}</template></el-table-column>
      <el-table-column label="剩余余额"><template #default="{ row }">{{ money(row.balanceAfterCents) }}</template></el-table-column>
      <el-table-column prop="parkingSessionId" label="停车记录" />
    </el-table>
  </section>
</template>
