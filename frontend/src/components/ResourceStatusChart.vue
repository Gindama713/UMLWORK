<script setup>
import { computed } from 'vue'
import EChartView from './EChartView.vue'
import { countSpaceStatuses } from './chartData.js'

const props = defineProps({ spaces: { type: Array, required: true } })
const types = [['NORMAL', '普通'], ['ACCESSIBLE', '无障碍'], ['CHARGING', '充电'], ['RESERVATION', '预约']]
const statuses = [['AVAILABLE', '可用', '#a9cb67'], ['OCCUPIED', '占用', '#607a9f'], ['OUT_OF_SERVICE', '停用', '#c8d1dd']]
const counts = computed(() => countSpaceStatuses(props.spaces))
const option = computed(() => ({
  animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  grid: { left: 58, right: 25, top: 10, bottom: 12 },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: '#192d48', borderWidth: 0, textStyle: { color: '#f5f8fc' } },
  xAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#8594a8', fontSize: 10 }, splitLine: { lineStyle: { color: '#eaf0f5', type: 'dashed' } } },
  yAxis: { type: 'category', data: types.map(([, label]) => label), axisLabel: { color: '#52657f', fontSize: 11 }, axisLine: { show: false }, axisTick: { show: false } },
  series: statuses.map(([status, name, color], index) => ({ name, type: 'bar', stack: 'spaces', barWidth: 14, data: types.map(([type]) => counts.value[type]?.[status] || 0), itemStyle: { color, borderRadius: index === 2 ? [0, 6, 6, 0] : 0 }, emphasis: { focus: 'series' } }))
}))
</script>

<template>
  <div v-if="spaces.length" class="resource-chart">
    <EChartView :option="option" :label="`当前车位状态：${statuses.map(([status, name]) => `${name} ${types.reduce((total, [type]) => total + (counts[type]?.[status] || 0), 0)} 个`).join('，')}`" />
    <div class="resource-legend"><span v-for="[status, name, color] in statuses" :key="status"><i :style="{ background: color }"></i>{{ name }}</span></div>
  </div>
  <p v-else class="empty-state">暂无车位资源</p>
</template>
