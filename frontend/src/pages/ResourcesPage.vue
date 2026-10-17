<script setup>
import { ref } from 'vue'
import SpaceMap from '../components/SpaceMap.vue'

// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const {
  types,
  label,
  busy,
  spaces,
  adminSpace,
  entry,
  page,
  recommendForm,
  recommendations,
  recommendSpaces,
  action,
  loadResources,
  createSpace,
  setSpaceStatus,
} = props.workspace
const recommendationHighlight = ref('')

async function showRecommendations() {
  await recommendSpaces()
  recommendationHighlight.value = recommendations.value[0]?.spaceId || ''
}
function useRecommendation(item) {
  entry.spaceType = item.type
  entry.preferredSpaceId = item.spaceId
  page.value = 'entry'
}
</script>

<template>
  <h2>车位分区与状态</h2>
  <SpaceMap
    :spaces="spaces"
    :highlight="recommendationHighlight || adminSpace.spaceId"
    @select="(space) => { adminSpace.spaceId = space.spaceId; recommendationHighlight = space.spaceId }"
  />
  <section class="panel recommendation-panel">
    <div class="panel-heading"><div><span class="section-kicker">FIND YOUR SPACE</span><h2>航站楼车位推荐</h2></div><span class="small-badge">只读建议</span></div>
    <div class="recommendation-controls">
      <el-select v-model="recommendForm.terminal" aria-label="目的航站楼"><el-option label="T1 航站楼" value="T1" /><el-option label="T2 航站楼" value="T2" /></el-select>
      <el-select v-model="recommendForm.type" aria-label="所需车位类型"><el-option label="普通车位" value="NORMAL" /><el-option label="无障碍车位" value="ACCESSIBLE" /><el-option label="充电车位" value="CHARGING" /></el-select>
      <el-button type="primary" :loading="busy" @click="showRecommendations">查找推荐</el-button>
    </div>
    <div v-if="recommendations.length" class="recommendation-list">
      <div v-for="(item, index) in recommendations" :key="item.spaceId" class="recommendation-card">
        <span class="recommendation-rank">{{ String(index + 1).padStart(2, '0') }}</span>
        <strong>{{ item.number }}</strong><span>{{ item.floor }} · {{ item.zone }}区</span>
        <span>{{ item.reason }}</span><small>资料版本 {{ item.dataVersion }}</small>
        <div class="recommendation-actions"><el-button @click="recommendationHighlight = item.spaceId">地图定位</el-button><el-button type="primary" @click="useRecommendation(item)">选择此车位入场</el-button></div>
      </div>
    </div>
    <p v-else class="form-note">查询后显示当前可用车位。</p>
    <p class="form-note">步行时间为课程演示估计，不代表真实导航；推荐不等于预约，最终是否可用以入场原子占位结果为准。</p>
  </section>
  <div class="toolbar">
    <el-button @click="action(loadResources)">刷新车位</el-button>
  </div>
  <el-table :data="spaces" stripe max-height="320">
    <el-table-column prop="number" label="编号" />
    <el-table-column prop="floor" label="楼层" />
    <el-table-column prop="zone" label="区域" />
    <el-table-column label="类型"
      ><template #default="{ row }">{{ types[row.type] }}</template></el-table-column
    >
    <el-table-column label="当前状态"
      ><template #default="{ row }"
        ><el-tag
          :type="
            row.status === 'AVAILABLE' ? 'success' : row.status === 'OCCUPIED' ? 'warning' : 'info'
          "
          >{{ label(row.status) }}</el-tag
        ></template
      ></el-table-column
    >
  </el-table>
  <h3>维护车位</h3>
  <div class="form-grid">
    <el-select v-model="adminSpace.type" aria-label="新增车位类型"
      ><el-option v-for="(title, type) in types" :key="type" :label="title" :value="type"
    /></el-select>
    <el-input v-model="adminSpace.floor" placeholder="楼层，例如 B1" />
    <el-input v-model="adminSpace.zone" placeholder="区域，例如 A" />
    <el-input v-model="adminSpace.number" placeholder="唯一编号，例如 A-101" />
    <el-button type="primary" :loading="busy" @click="createSpace">新增车位</el-button>
  </div>
  <div class="form-grid subform">
    <el-select v-model="adminSpace.spaceId" placeholder="选择车位" filterable
      ><el-option
        v-for="space in spaces"
        :key="space.spaceId"
        :label="space.number"
        :value="space.spaceId"
    /></el-select>
    <el-select v-model="adminSpace.status"
      ><el-option label="停用" value="OUT_OF_SERVICE" /><el-option
        label="恢复可用"
        value="AVAILABLE"
    /></el-select>
    <el-button :loading="busy" @click="setSpaceStatus">更新状态</el-button>
  </div>
</template>
