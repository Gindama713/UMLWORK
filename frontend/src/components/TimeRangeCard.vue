<script setup>
import { computed, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import { quickRange, rangeInfo } from './timeRange.js'

const props = defineProps({
  start: { type: String, default: '' },
  end: { type: String, default: '' },
  title: { type: String, required: true },
  headingLevel: { type: String, default: 'h3' },
  startLabel: { type: String, required: true },
  endLabel: { type: String, required: true },
  mode: { type: String, default: 'duration' },
  future: { type: Boolean, default: false },
  maxDays: { type: Number, default: 0 },
})
const emit = defineEmits(['update:start', 'update:end'])
const activePreset = ref(null)
const info = computed(() => rangeInfo(props.start, props.end, {
  future: props.future,
  maxDays: props.maxDays,
}))
const presets = computed(() => props.mode === 'history'
  ? [
      { value: 'past-24', label: '近24小时' },
      { value: 'past-168', label: '近7天' },
      { value: 'past-720', label: '近30天' },
    ]
  : [
      { value: 1, label: '1小时' },
      { value: 2, label: '2小时' },
      { value: 4, label: '4小时' },
      { value: 'overnight', label: '至次日08:00' },
    ])

function selectPreset(preset) {
  const range = quickRange(props.start, preset, { future: props.future })
  emit('update:start', range.start)
  emit('update:end', range.end)
  activePreset.value = preset
}

function update(field, value) {
  activePreset.value = null
  emit(`update:${field}`, value)
}
</script>

<template>
  <section class="time-range-card" :aria-label="title">
    <div class="time-range-heading">
      <div>
        <span class="time-range-kicker"><AppIcon name="clock" /> 时间安排</span>
        <component :is="headingLevel">{{ title }}</component>
      </div>
      <span class="time-range-duration" :class="{ invalid: !info.valid }">
        {{ info.duration || '待选择' }}
      </span>
    </div>
    <div class="time-range-fields">
      <label class="time-range-field">
        <span><b>01</b>{{ startLabel }}</span>
        <input :value="start" type="datetime-local" step="60" @input="update('start', $event.target.value)" />
      </label>
      <span class="time-range-connector" aria-hidden="true">→</span>
      <label class="time-range-field">
        <span><b>02</b>{{ endLabel }}</span>
        <input :value="end" type="datetime-local" step="60" @input="update('end', $event.target.value)" />
      </label>
    </div>
    <div class="time-range-shortcuts">
      <span>快捷选择</span>
      <button
        v-for="preset in presets"
        :key="preset.value"
        type="button"
        :aria-pressed="activePreset === preset.value"
        @click="selectPreset(preset.value)"
      >{{ preset.label }}</button>
    </div>
    <p class="time-range-feedback" :class="{ invalid: !info.valid }" aria-live="polite">{{ info.message }}</p>
    <slot :valid="info.valid" />
  </section>
</template>
