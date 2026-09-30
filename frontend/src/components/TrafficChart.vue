<script setup>
import { computed } from 'vue'
const props = defineProps({ report: Object })
const max = computed(() => Math.max(1, ...(props.report?.buckets || []).map(b => b.count)))
const format = value => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
</script>
<template>
  <div v-if="report" class="traffic-visual">
    <div class="chart-summary"><span>入场车辆 <strong>{{ report.totalEntries }}</strong> 辆</span><span>高峰 {{ report.peakBucket ? format(report.peakBucket.start) : '暂无入场' }}</span></div>
    <div class="traffic-chart" role="img" :aria-label="`车流统计，总入场 ${report.totalEntries} 辆，最高单时段 ${report.peakBucket?.count || 0} 辆`">
      <div v-for="(bucket, index) in report.buckets" :key="bucket.start" class="traffic-column" :title="`${format(bucket.start)}：${bucket.count} 辆`">
        <div class="bar-track"><span class="bar-value" v-if="bucket.count">{{ bucket.count }}</span><div class="traffic-bar" :class="{ peak: report.peakBucket?.start === bucket.start }" :style="{ height: `${bucket.count / max * 100}%` }"></div></div>
        <small>{{ index % Math.max(1, Math.ceil(report.buckets.length / 6)) === 0 ? format(bucket.start) : '' }}</small>
      </div>
    </div>
    <p class="map-caption">按入场时刻计数 · 上海时间 · 鼠标悬停查看各时段数量</p>
  </div>
  <p v-else class="empty-state">尚未取得车流数据</p>
</template>
