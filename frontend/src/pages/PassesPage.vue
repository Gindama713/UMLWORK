<script setup>
// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const {
  page,
  label,
  busy,
  spaces,
  reservation,
  monthly,
  monthlyLedger,
  entry,
  reserve,
  pass,
  money,
  createReservation,
  payReservation,
  cancelReservation,
  createPass,
  refreshPass,
} = props.workspace
</script>

<template>
  <h3>预约指定车位</h3>
  <div class="form-grid">
    <el-input v-model="reserve.plateNumber" placeholder="车牌号" />
    <el-select v-model="reserve.spaceId" placeholder="预约位"
      ><el-option
        v-for="space in spaces.filter((s) => s.type === 'RESERVATION')"
        :key="space.spaceId"
        :label="space.number"
        :value="space.spaceId"
    /></el-select>
    <label>开始 <input v-model="reserve.startTime" type="datetime-local" step="0.001" /></label>
    <label>结束 <input v-model="reserve.endTime" type="datetime-local" step="0.001" /></label>
    <el-button type="primary" :loading="busy" @click="createReservation">创建预约</el-button>
  </div>
  <div v-if="reservation" class="result-card">
    <el-tag>{{ label(reservation.status) }}</el-tag>
    <h3>预约凭证</h3>
    <p>预付金额 {{ money(reservation.prepaidCents) }}</p>
    <code>{{ reservation.reservationId }}</code>
    <p class="muted">支付成功后可使用预约凭证登记入场。</p>
    <el-button v-if="reservation.status === 'CONFIRMED'" @click="page = 'entry'"
      >使用预约入场 →</el-button
    >
  </div>
  <div class="toolbar">
    <el-button :loading="busy" @click="payReservation('SUCCESS')">模拟预付成功</el-button>
    <el-button :loading="busy" @click="payReservation('FAILURE')">模拟预付失败</el-button>
    <el-button :loading="busy" @click="cancelReservation">取消预约</el-button>
  </div>
  <h3>开通月卡</h3>
  <div class="form-grid">
    <el-input v-model="pass.plateNumber" placeholder="绑定车牌" />
    <label>生效 <input v-model="pass.startTime" type="datetime-local" step="0.001" /></label>
    <el-button :loading="busy" @click="createPass">创建月卡</el-button>
  </div>
  <p v-if="monthly">月卡 {{ monthly.monthlyPassId }} · 余额 {{ money(monthly.balanceCents) }}</p>
  <el-button v-if="monthly" :loading="busy" @click="refreshPass">刷新余额与扣费流水</el-button>
  <el-table v-if="monthlyLedger.length" :data="monthlyLedger" stripe max-height="220">
    <el-table-column prop="kind" label="流水类型" />
    <el-table-column label="金额"
      ><template #default="{ row }">{{ money(row.amountCents) }}</template></el-table-column
    >
    <el-table-column label="剩余余额"
      ><template #default="{ row }">{{ money(row.balanceAfterCents) }}</template></el-table-column
    >
    <el-table-column prop="parkingSessionId" label="停车记录" />
  </el-table>
</template>
