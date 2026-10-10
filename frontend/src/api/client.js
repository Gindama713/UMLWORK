// One request key per unfinished write. Retrying the same payload after a server
// failure reuses the key, so the backend can return the original result.
const pendingKeys = new Map()

export async function api(path, method = 'GET', body) {
  const payload = method === 'GET' ? undefined : JSON.stringify(body ?? {})
  const request = `${method}:${path}:${payload}`
  if (method !== 'GET' && !pendingKeys.has(request)) {
    pendingKeys.set(request, crypto.randomUUID())
  }

  const response = await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(method === 'GET' ? {} : { 'Idempotency-Key': pendingKeys.get(request) }),
    },
    body: payload,
    cache: 'no-store',
  })
  const result = await response.json().catch(() => null)
  const success = response.ok && result?.code === 'OK'
  if (success || (response.status >= 400 && response.status < 500)) pendingKeys.delete(request)
  if (!success) {
    const fallback = response.ok
      ? '服务响应格式异常，请重试'
      : `服务暂不可用（HTTP ${response.status}）`
    throw new Error(result?.message || result?.code || fallback)
  }
  return result.data
}
