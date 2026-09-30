<script setup>
import { computed, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
const props = defineProps({ spaces: { type: Array, required: true }, highlight: String })
const emit = defineEmits(['select'])
const floor = ref('*')
const selectedId = ref('')
const floors = computed(() => [...new Set(props.spaces.map(space => space.floor))])
const zones = computed(() => {
  const visible = props.spaces.filter(s => floor.value === '*' || s.floor === floor.value)
  const groups = new Map()
  for (const space of visible) {
    const key = `${space.floor} · ${space.zone}区`
    if (!groups.has(key)) groups.set(key, { name: key, spaces: [] })
    groups.get(key).spaces.push(space)
  }
  return [...groups.values()]
})
const selected = computed(() => props.spaces.find(s => s.spaceId === selectedId.value))
const types = { NORMAL: '普通', ACCESSIBLE: '无障碍', CHARGING: '充电', RESERVATION: '预约' }
const statuses = { AVAILABLE: '可用', OCCUPIED: '占用', OUT_OF_SERVICE: '停用' }
watch(() => props.highlight, id => {
  const space = props.spaces.find(s => s.spaceId === id)
  if (space) { floor.value = space.floor; selectedId.value = id }
}, { immediate: true })
function select(space) { selectedId.value = space.spaceId; emit('select', space) }
</script>
<template>
  <div class="parking-map">
    <div class="map-tools">
      <div class="floor-switch" aria-label="选择楼层">
        <button :class="{ selected: floor === '*' }" :aria-pressed="floor === '*'" @click="floor = '*'">全场</button>
        <button v-for="item in floors" :key="item" :class="{ selected: item === floor }" :aria-pressed="item === floor" @click="floor = item">{{ item }}</button>
      </div>
      <div class="map-legend"><span class="available">可用</span><span class="occupied">占用</span><span class="offline">停用</span></div>
    </div>
    <div class="garage-canvas">
      <div class="garage-sign"><AppIcon name="plane" /><span>AIRPORT PARKING</span><span class="sign-arrow">↗</span></div>
      <p v-if="!spaces.length" class="empty-state">暂无车位，请在资源管理中添加。</p>
      <div class="garage-zones">
        <section v-for="zone in zones" :key="zone.name" class="parking-zone">
          <div class="zone-heading"><strong>{{ zone.name }}</strong><span>{{ zone.spaces.filter(s => s.status === 'AVAILABLE').length }} 可用</span></div>
          <div class="bay-grid">
            <button v-for="space in zone.spaces" :key="space.spaceId" class="parking-bay" :class="[space.status.toLowerCase(), { highlighted: space.spaceId === selectedId || space.spaceId === highlight }]"
              :aria-pressed="space.spaceId === selectedId" :aria-label="`${space.floor} ${space.zone}区 ${space.number} ${types[space.type]} ${statuses[space.status]}`" @click="select(space)">
              <svg v-if="space.status === 'OCCUPIED'" class="car-plan" viewBox="0 0 36 68" aria-hidden="true"><rect x="5" y="3" width="26" height="62" rx="8" fill="currentColor"/><path d="M9 17h18l-2 10H11z M11 47h14l2 8H9z" fill="#dce6f0"/><path d="M7 30v14 M29 30v14" stroke="#dce6f0" stroke-width="2"/><rect x="1" y="16" width="4" height="7" rx="1" fill="currentColor"/><rect x="31" y="16" width="4" height="7" rx="1" fill="currentColor"/></svg>
              <AppIcon v-else :name="space.type === 'CHARGING' ? 'bolt' : space.type === 'ACCESSIBLE' ? 'accessible' : space.type === 'RESERVATION' ? 'ticket' : 'parking'" />
              <strong>{{ space.number }}</strong><small>{{ types[space.type] }}</small>
              <span class="bay-status-dot" :title="statuses[space.status]"></span>
            </button>
          </div>
          <div class="zone-driveway" aria-hidden="true"><span></span><AppIcon name="arrow" /><span></span></div>
        </section>
      </div>
      <div class="garage-caption"><AppIcon name="layers" /><span>楼层区域示意 · 点击车位查看详情</span></div>
    </div>
    <div class="space-inspector" v-if="selected"><div class="inspector-pin"><AppIcon name="pin" /></div><div><strong>{{ selected.number }}</strong><span>{{ selected.floor }} · {{ selected.zone }}区 · {{ types[selected.type] }}车位</span></div><span class="space-state" :class="selected.status.toLowerCase()">{{ statuses[selected.status] }}</span></div>
    <p class="map-caption">由实际车位资料生成；布局与道路仅为示意，不代表建筑平面或导航路线。</p>
  </div>
</template>
