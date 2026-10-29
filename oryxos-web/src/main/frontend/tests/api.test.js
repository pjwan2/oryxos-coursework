import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, query } from '../src/api.js'
import { formatUptime, schemaParameters } from '../src/format.js'

function respond(status, body, headers = {}) {
  return Promise.resolve(
    new Response(typeof body === 'string' ? body : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json', ...headers }
    })
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('api()', () => {
  it('unwraps the envelope data', async () => {
    vi.stubGlobal('fetch', vi.fn(() => respond(200, { code: 0, message: 'ok', data: [1, 2] })))
    expect(await api('/profiles')).toEqual([1, 2])
    expect(fetch).toHaveBeenCalledWith('/api/v1/profiles', expect.objectContaining({ method: 'GET' }))
  })

  it('sends JSON bodies with a content type', async () => {
    vi.stubGlobal('fetch', vi.fn(() => respond(200, { code: 0, data: {} })))
    await api('/schedules/a.b', { method: 'PUT', body: { enabled: false } })
    const [, init] = fetch.mock.calls[0]
    expect(init.body).toBe('{"enabled":false}')
    expect(init.headers['Content-Type']).toBe('application/json')
  })

  it('turns an error envelope into an ApiError with status and trace id', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => respond(404, { code: 404, message: 'Session not found' }, { 'X-Trace-Id': 'abc' }))
    )
    const failure = await api('/sessions/x').catch((e) => e)
    expect(failure).toBeInstanceOf(ApiError)
    expect(failure.status).toBe(404)
    expect(failure.message).toBe('Session not found')
    expect(failure.traceId).toBe('abc')
  })

  it('does not show a raw non-JSON body', async () => {
    vi.stubGlobal('fetch', vi.fn(() => respond(502, '<html>stack trace at com.x.Y</html>')))
    const failure = await api('/info').catch((e) => e)
    expect(failure.message).toBe('Unexpected response (HTTP 502)')
  })

  it('reports an unreachable server in plain words', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))
    const failure = await api('/info').catch((e) => e)
    expect(failure.status).toBe(0)
    expect(failure.message).toMatch(/Cannot reach/)
  })
})

describe('helpers', () => {
  it('query() drops empty values and encodes the rest', () => {
    expect(query({ a: 1, b: undefined, c: null, d: 'x y' })).toBe('?a=1&d=x+y')
    expect(query({})).toBe('')
  })

  it('schemaParameters() reads required flags, types and enums', () => {
    const rows = schemaParameters({
      type: 'object',
      properties: { url: { type: 'string', description: 'Where' }, scope: { enum: ['A', 'B'] } },
      required: ['url']
    })
    expect(rows).toEqual([
      { name: 'url', type: 'string', required: true, description: 'Where', allowed: '' },
      { name: 'scope', type: '—', required: false, description: '', allowed: 'A, B' }
    ])
    expect(schemaParameters('not json')).toEqual([])
    expect(schemaParameters(null)).toEqual([])
  })

  it('formatUptime() picks a readable unit', () => {
    expect(formatUptime(42)).toBe('42s')
    expect(formatUptime(125)).toBe('2m 5s')
    expect(formatUptime(3 * 3600 + 120)).toBe('3h 2m')
    expect(formatUptime(90000)).toBe('1d 1h 0m')
  })
})
