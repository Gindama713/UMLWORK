import test from 'node:test'
import assert from 'node:assert/strict'
import { countSpaceStatuses } from './chartData.js'

test('resource chart groups the actual spaces by type and status', () => {
  const spaces = [
    { type: 'NORMAL', status: 'AVAILABLE' },
    { type: 'NORMAL', status: 'AVAILABLE' },
    { type: 'NORMAL', status: 'OCCUPIED' },
    { type: 'CHARGING', status: 'OUT_OF_SERVICE' },
  ]
  assert.deepEqual(countSpaceStatuses(spaces), {
    NORMAL: { AVAILABLE: 2, OCCUPIED: 1 },
    CHARGING: { OUT_OF_SERVICE: 1 },
  })
  assert.equal(spaces.length, 4)
})
