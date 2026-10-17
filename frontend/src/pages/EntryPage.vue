<script setup>
import AppIcon from '../components/AppIcon.vue'
import SpaceMap from '../components/SpaceMap.vue'

// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const {
  page,
  types,
  label,
  busy,
  spaces,
  session,
  charging,
  typeIcon,
  entry,
  search,
  dateTime,
  enter,
  recognizePlate,
  locate,
} = props.workspace

function uploadPlate(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) recognizePlate(file)
}
function selectType(type) {
  if (entry.spaceType !== type) entry.preferredSpaceId = ''
  entry.spaceType = type
}
</script>

<template>
  <div class="entry-layout">
    <div class="entry-controls">
      <section class="panel entry-form">
        <div class="panel-heading">
          <div>
            <span class="section-kicker">01 / CHECK IN</span>
            <h2>车辆入场登记</h2>
          </div>
          <span class="small-badge">人工确认入场</span>
        </div>
        <label class="field-label" for="entry-plate">车牌号码</label
        ><el-input
          id="entry-plate"
          v-model="entry.plateNumber"
          class="plate-input"
          placeholder="请输入车牌号"
          maxlength="12"
          @keyup.enter="enter"
        />
        <div class="plate-recognition">
          <label class="plate-upload">
            <AppIcon name="search" /> 上传车牌照片辅助识别
            <input type="file" accept="image/jpeg,image/png" :disabled="busy" @change="uploadPlate" />
          </label>
          <p>图片会发往第三方识别服务。仅回填候选车牌，请核对后再登记；服务不可用时可直接手动输入。</p>
        </div>
        <span class="field-label">车位类型</span>
        <div class="type-picker">
          <button
            v-for="(title, type) in types"
            :key="type"
            :class="{ selected: entry.spaceType === type }"
            :aria-pressed="entry.spaceType === type"
            @click="selectType(type)"
          >
            <AppIcon :name="typeIcon(type)" /><span>{{ title }}</span>
          </button>
        </div>
        <div v-if="entry.preferredSpaceId" class="selected-space-hint">
          已选择推荐车位。入场前会再次核验可用状态；若已被占用，请刷新推荐。
          <button type="button" class="text-button" @click="entry.preferredSpaceId = ''">取消指定</button>
        </div>
        <label v-if="entry.spaceType === 'RESERVATION'" class="stacked-field"
          >预约凭证<el-input v-model="entry.reservationId" placeholder="已确认预约的 ID"
        /></label>
        <details class="advanced-options">
          <summary>演示时间设置</summary>
          <label class="stacked-field"
            >入场时间（留空使用当前时刻）<input
              v-model="entry.entryTime"
              type="datetime-local"
              step="0.001"
          /></label>
        </details>
        <el-button
          class="full-button"
          type="primary"
          :loading="busy"
          :disabled="!entry.plateNumber.trim()"
          @click="enter"
          >登记入场<AppIcon name="arrow"
        /></el-button>
        <p class="form-note">可用车位与预约资格由服务端核验。</p>
      </section>
      <section class="panel search-panel">
        <div class="panel-heading">
          <div>
            <span class="section-kicker">02 / FIND MY CAR</span>
            <h2>反向寻车</h2>
          </div>
          <AppIcon name="pin" />
        </div>
        <label class="field-label" for="locate-plate">在场车辆车牌</label>
        <div class="search-row">
          <el-input
            id="locate-plate"
            v-model="search.plateNumber"
            placeholder="按车牌反向寻车"
            @keyup.enter="locate"
          /><el-button :loading="busy" :disabled="!search.plateNumber.trim()" @click="locate"
            ><AppIcon name="search" />查找</el-button
          >
        </div>
      </section>
    </div>
    <section class="panel location-panel">
      <div class="panel-heading">
        <div>
          <span class="section-kicker">PARKING POSITION</span>
          <h2>{{ session?.status === 'CLOSED' ? '本次停车已结束' : '车辆位置' }}</h2>
        </div>
        <el-tag v-if="session" :type="session.status === 'CLOSED' ? 'info' : 'success'">{{
          label(session.status)
        }}</el-tag>
      </div>
      <template v-if="session"
        ><div class="location-hero">
          <span class="license-plate">{{ session.plateNumber }}</span>
          <div class="location-number">
            <span class="location-p">P</span><strong>{{ session.spaceNumber }}</strong
            ><AppIcon name="car" />
          </div>
          <div class="location-meta">
            <span><AppIcon name="layers" />{{ session.floor }} 层</span
            ><span><AppIcon name="pin" />{{ session.zone }} 区</span>
          </div>
        </div>
        <div class="location-time">
          <AppIcon name="clock" /><span>入场 {{ dateTime(session.entryTime) }}</span>
        </div>
        <SpaceMap
          :spaces="spaces"
          :highlight="session.status === 'CLOSED' ? undefined : session.spaceId"
        />
        <div class="location-actions">
          <button class="text-button" v-if="session.status !== 'CLOSED'" @click="page = 'charging'">
            充电服务<AppIcon name="arrowUp" /></button
          ><el-button v-if="session.status !== 'CLOSED'" type="primary" @click="page = 'billing'"
            >前往出场结算<AppIcon name="arrow"
          /></el-button>
        </div>
        <details class="advanced-options">
          <summary>停车记录详情</summary>
          <code>{{ session.parkingSessionId }}</code>
          <p v-if="session.status === 'CLOSED'" class="muted">
            上方为本次历史停车位置，车辆已出场。
          </p>
        </details></template
      >
      <div v-else class="location-empty">
        <div class="empty-car"><AppIcon name="car" /><span>P</span></div>
        <h3>先找到车，再开始下一程</h3>
        <p>登记入场或输入在场车牌<br />这里将显示楼层、区域与车位编号。</p>
      </div>
    </section>
  </div>
</template>
