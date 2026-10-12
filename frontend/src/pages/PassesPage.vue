<script setup>
import AppIcon from '../components/AppIcon.vue'

const props = defineProps({ workspace: { type: Object, required: true } })
const {
  page, label, busy, spaces, reservation, monthly, monthlyLedger, reserve, pass,
  money, dateTime, createReservation, payReservation, refreshReservation, cancelReservation,
  createPass, refreshPass,
} = props.workspace
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
      <el-button class="service-primary" type="primary" :loading="busy" @click="createReservation">创建预约</el-button>

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
