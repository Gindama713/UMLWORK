<script setup>
import { computed, ref } from 'vue'
import EChartView from './EChartView.vue'

const props = defineProps({ report: Object })
const mode = ref('line')
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
const format = value => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
const buckets = computed(() => props.report?.buckets || [])
const option = computed(() => ({
  animation: !reduceMotion,
  animationDuration: 500,
  grid: { left: 36, right: 16, top: 25, bottom: buckets.value.length > 48 ? 55 : 36 },
  tooltip: {
    trigger: 'axis',
    backgroundColor: '#192d48', borderWidth: 0, textStyle: { color: '#f5f8fc' },
    formatter: params => params.length ? `${format(buckets.value[params[0].dataIndex].start)}<br/>入场车辆 <b>${params[0].value} 辆</b>` : ''
  },
  xAxis: {
    type: 'category', boundaryGap: mode.value === 'bar',
    data: buckets.value.map(bucket => format(bucket.start)),
    axisLine: { lineStyle: { color: '#dce4ed' } }, axisTick: { show: false },
    axisLabel: { color: '#8594a8', fontSize: 10, hideOverlap: true, margin: 12 }
  },
  yAxis: {
    type: 'value', minInterval: 1, min: 0,
    axisLabel: { color: '#8594a8', fontSize: 10 }, axisLine: { show: false }, axisTick: { show: false },
    splitLine: { lineStyle: { color: '#eaf0f5', type: 'dashed' } }
  },
  dataZoom: buckets.value.length > 48 ? [{ type: 'slider', bottom: 5, height: 18, borderColor: 'transparent', fillerColor: '#dceac9', handleStyle: { color: '#91b450' }, textStyle: { color: '#8291a6' } }] : [],
  series: [{
    type: mode.value, name: '入场车辆', data: buckets.value.map(bucket => bucket.count),
    smooth: mode.value === 'line' ? 0.28 : false, showSymbol: false,
    lineStyle: { width: 3, color: '#7ca34a' }, itemStyle: { color: '#8daf58', borderRadius: [5, 5, 0, 0] },
    barMaxWidth: 24,
    areaStyle: mode.value === 'line' ? { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#b9d77e99' }, { offset: 1, color: '#b9d77e0a' }] } } : undefined
  }]
}))
</script>

<template>
  <div v-if="report" class="traffic-visual">
    <div class="chart-toolbar">
      <div class="chart-summary"><span>入场车辆 <strong>{{ report.totalEntries }}</strong> 辆</span><span>高峰 {{ report.peakBucket ? format(report.peakBucket.start) : '暂无入场' }}</span></div>
      <div class="chart-switch" role="group" aria-label="车流图形类型">
        <button :class="{ selected: mode === 'line' }" :aria-pressed="mode === 'line'" @click="mode = 'line'">趋势</button>
        <button :class="{ selected: mode === 'bar' }" :aria-pressed="mode === 'bar'" @click="mode = 'bar'">柱形</button>
      </div>
    </div>
    <EChartView v-if="buckets.length" :option="option" :label="`车流统计，总入场 ${report.totalEntries} 辆，最高时段 ${report.peakBucket?.count || 0} 辆`" />
    <p v-else class="empty-state">所选时间范围暂无车流时段</p>
    <p class="map-caption">按实际入场时刻计数 · 上海时间 · 悬停查看各时段数量</p>
  </div>
  <p v-else class="empty-state">尚未取得车流数据</p>
</template>
