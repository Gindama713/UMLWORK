<script setup>
import { defineAsyncComponent } from 'vue'
const TrafficChart = defineAsyncComponent(() => import('../components/TrafficChart.vue'))

// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const { busy, report, trafficInsight, analytics, dateTime, traffic, explainTraffic } = props.workspace
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
  <section v-if="report" class="analytics-result" aria-label="车流报表结果">
    <div class="analytics-result-heading"><div><span class="section-kicker">TRAFFIC INTELLIGENCE</span><h3>车流时段分析</h3></div><span class="analytics-report-id">报表 #{{ report.reportId }}</span></div>
    <TrafficChart :report="report" />
    <p class="muted">统计范围 {{ dateTime(report.from) }} 至 {{ dateTime(report.to) }}</p>
    <div class="insight-card">
      <div class="insight-card-heading">
        <div><span class="section-kicker">AI / OPERATIONS</span><h3>高峰解读与运营建议</h3></div>
        <el-button type="primary" :loading="busy" @click="explainTraffic">生成 AI 解读</el-button>
      </div>
      <template v-if="trafficInsight && trafficInsight.reportId === report.reportId">
        <p class="insight-summary">{{ trafficInsight.summary }}</p>
        <ol><li v-for="(item, index) in trafficInsight.actions" :key="index">{{ item }}</li></ol>
        <span class="insight-source">{{ trafficInsight.model }} · AI 建议，仅依据本报表汇总数据；实际运营决策请人工核对。</span>
      </template>
      <p v-else class="muted">按需生成解读。车流数字以上方原始报表为准；模型不可用时仍可查看图表和明细。</p>
    </div>
    <h3 class="analytics-table-title">时段明细</h3>
    <el-table :data="report.buckets" stripe max-height="300">
      <el-table-column label="时段开始（上海时间）"><template #default="{ row }">{{ dateTime(row.start) }}</template></el-table-column>
      <el-table-column prop="count" label="入场数" />
    </el-table>
  </section>
  <p v-else class="empty-state">选择时间范围，生成可复核的车流报表。</p>
</template>
