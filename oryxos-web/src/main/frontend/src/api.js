// Thin client for the OryxOS REST API (`/api/v1`). Every answer uses the ApiResponse envelope
// `{ code, message, data, timestamp }`; anything else, or a non-2xx status, becomes an ApiError.
// Messages shown to the user are the server's own safe texts, never raw exception text.

export class ApiError extends Error {
  constructor(status, message, traceId = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.traceId = traceId
  }
}

export async function api(path, { method = 'GET', body } = {}) {
  let response
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch {
    throw new ApiError(0, 'Cannot reach the OryxOS server. Is it running?')
  }
  const traceId = response.headers.get('X-Trace-Id')
  let payload = null
  try {
    payload = await response.json()
  } catch {
    // Not JSON (for example an HTML error page from a proxy): fall through to the status text.
  }
  if (!response.ok || payload === null || payload.code !== 0) {
    const message = payload?.message ?? `Unexpected response (HTTP ${response.status})`
    throw new ApiError(response.status, message, traceId)
  }
  return payload.data
}

export const query = (params) => {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) search.set(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}
