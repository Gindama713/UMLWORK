<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'

echarts.use([BarChart, LineChart, DataZoomComponent, GridComponent, TooltipComponent, SVGRenderer])

const props = defineProps({ option: { type: Object, required: true }, label: { type: String, required: true } })
const host = ref(null)
let chart
let observer

onMounted(() => {
  chart = echarts.init(host.value, null, { renderer: 'svg' })
  chart.setOption(props.option)
  observer = new ResizeObserver(() => chart.resize())
  observer.observe(host.value)
})

watch(() => props.option, option => chart?.setOption(option, { notMerge: true }))
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>

<template>
  <div role="img" :aria-label="label"><div ref="host" class="echart-view" aria-hidden="true"></div></div>
</template>
