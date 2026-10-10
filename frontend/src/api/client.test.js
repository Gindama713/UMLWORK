import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { api } from './client.js'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
})

test('server failure keeps the idempotency key for a safe retry', async () => {
  const keys = []
  globalThis.fetch = async (_path, options) => {
    keys.push(options.headers['Idempotency-Key'])
    return new Response(
      JSON.stringify(
        keys.length === 1
          ? { code: 'DEPENDENCY_UNAVAILABLE', message: '暂不可用' }
          : { code: 'OK', data: { status: 'CLOSED' } },
      ),
      {
        status: keys.length === 1 ? 503 : 200,
        headers: { 'Content-Type': 'application/json' },
      },
    )
  }

  await assert.rejects(api('/api/v1/access/retry-check', 'POST', { value: 1 }), /暂不可用/)
  assert.deepEqual(await api('/api/v1/access/retry-check', 'POST', { value: 1 }), {
    status: 'CLOSED',
  })
  assert.equal(keys[0], keys[1])
})

test('an HTML error page produces a readable HTTP error', async () => {
  globalThis.fetch = async () => new Response('<html>Not Found</html>', { status: 404 })
  await assert.rejects(api('/api/v1/missing'), /HTTP 404/)
})

test('a malformed success response keeps the key until the result is known', async () => {
  const keys = []
  globalThis.fetch = async (_path, options) => {
    keys.push(options.headers['Idempotency-Key'])
    return new Response(
      keys.length === 1 ? '<html>unexpected</html>' : JSON.stringify({ code: 'OK', data: {} }),
      {
        status: 200,
      },
    )
  }

  await assert.rejects(api('/api/v1/access/uncertain-check', 'POST', {}), /响应格式异常/)
  await api('/api/v1/access/uncertain-check', 'POST', {})
  assert.equal(keys[0], keys[1])
})
