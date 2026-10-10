<script setup>
import SpaceMap from '../components/SpaceMap.vue'

// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const {
  types,
  label,
  busy,
  spaces,
  adminSpace,
  action,
  loadResources,
  createSpace,
  setSpaceStatus,
} = props.workspace
</script>

<template>
  <h2>车位分区与状态</h2>
  <SpaceMap
    :spaces="spaces"
    :highlight="adminSpace.spaceId"
    @select="(space) => (adminSpace.spaceId = space.spaceId)"
  />
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
