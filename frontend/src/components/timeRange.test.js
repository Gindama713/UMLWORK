import assert from 'node:assert/strict'
import { test } from 'node:test'
import { quickRange, rangeInfo } from './timeRange.js'

test('quick duration and overnight preserve the selected local start', () => {
  const twoHours = quickRange('2026-11-02T22:30', 2)
  assert.deepEqual(twoHours, { start: '2026-11-02T22:30', end: '2026-11-03T00:30' })
  assert.deepEqual(quickRange('2026-11-02T22:30', 'overnight'), {
    start: '2026-11-02T22:30', end: '2026-11-03T08:00',
  })
  assert.equal(rangeInfo(twoHours.start, twoHours.end).duration, '2小时')
})

test('report presets and invalid intervals respect the API range limit', () => {
  const now = new Date('2026-11-02T10:00:00+08:00')
  const range = quickRange('', 'past-720', { now })
  assert.equal(rangeInfo(range.start, range.end, { maxDays: 31 }).valid, true)
  assert.equal(rangeInfo('2026-11-02T10:00', '2026-11-02T10:00').valid, false)
  assert.match(rangeInfo('2026-10-01T10:00', '2026-11-02T10:00', { maxDays: 31 }).message, /最多 31 天/)
})

test('a stale reservation start is moved to the next hour by a quick choice', () => {
  const now = new Date(2026, 10, 2, 10, 12)
  const range = quickRange('2026-10-01T08:00', 2, { future: true, now })
  assert.equal(range.start.slice(11), '11:00')
  assert.equal(Date.parse(range.start) > now.getTime(), true)
})
