<script setup>
import TrafficChart from '../components/TrafficChart.vue'

// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const { label, busy, report, analytics, dateTime, traffic } = props.workspace
</script>

<template>
  <h2>车流量与高峰统计</h2>
  <p class="muted">按入场时刻统计，时间区间左闭右开；生成报表会保存可复核快照。</p>
  <div class="form-grid">
    <label>开始 <input v-model="analytics.from" type="datetime-local" step="0.001" /></label>
    <label>结束 <input v-model="analytics.to" type="datetime-local" step="0.001" /></label>
    <el-select v-model="analytics.granularity"
      ><el-option label="按小时" value="HOUR" /><el-option label="按天" value="DAY"
    /></el-select>
    <el-button type="primary" :loading="busy" @click="traffic">生成车流报表</el-button>
  </div>
  <TrafficChart :report="report" />
  <p v-if="report" class="muted">
    报表编号 {{ report.reportId }} · 范围 {{ dateTime(report.from) }} 至
    {{ dateTime(report.to) }}
  </p>
  <el-table v-if="report" :data="report.buckets" stripe max-height="300">
    <el-table-column label="时段开始（上海时间）"
      ><template #default="{ row }">{{ dateTime(row.start) }}</template></el-table-column
    ><el-table-column prop="count" label="入场数" />
  </el-table>
</template>
