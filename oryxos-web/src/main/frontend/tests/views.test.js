// Page behaviour with a stubbed network layer. The stubs live only in this test file: the views
// themselves contain no data and always call the real API.
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '../src/router.js'
import MemoryView from '../src/views/MemoryView.vue'
import ProfilesView from '../src/views/ProfilesView.vue'
import SchedulerView from '../src/views/SchedulerView.vue'
import SessionsView from '../src/views/SessionsView.vue'
import SystemView from '../src/views/SystemView.vue'
import ToolsView from '../src/views/ToolsView.vue'

function envelope(data) {
  return { code: 0, message: 'ok', data }
}

/** Routes "METHOD /path" (path without the /api/v1 prefix, query included) to a handler. */
function stubApi(table) {
  const calls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url, init = {}) => {
      const key = `${init.method ?? 'GET'} ${decodeURIComponent(String(url)).replace('/api/v1', '')}`
      calls.push(key)
      const handler = Object.entries(table).find(([pattern]) => key.startsWith(pattern))?.[1]
      if (!handler) return new Response(JSON.stringify({ code: 404, message: `no stub ${key}` }), { status: 404 })
      const result = typeof handler === 'function' ? handler(init) : handler
      return new Response(JSON.stringify(result.body ?? result), { status: result.status ?? 200 })
    })
  )
  return calls
}

async function show(component, path = '/') {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  const wrapper = mount(component, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

afterEach(() => vi.unstubAllGlobals())

describe('loading, empty and error states', () => {
  it('shows loading before the answer arrives', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    const wrapper = await show(ProfilesView)
    expect(wrapper.find('[data-test=loading]').exists()).toBe(true)
  })

  it('shows an empty message for an empty list', async () => {
    stubApi({ 'GET /profiles': envelope([]) })
    const wrapper = await show(ProfilesView)
    expect(wrapper.find('[data-test=empty]').text()).toContain('No profiles')
  })

  it('shows the server message, status and trace id on error, and can retry', async () => {
    let attempt = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        attempt += 1
        return attempt === 1
          ? new Response(JSON.stringify({ code: 503, message: 'Memory backend unavailable' }), {
              status: 503,
              headers: { 'X-Trace-Id': 'trace-9' }
            })
          : new Response(JSON.stringify(envelope({ backend: 'markdown', core: [], archival: [{ scope: 'ARCHIVAL', content: 'x', savedOn: null }], archivalLimit: 100 })))
      })
    )
    const wrapper = await show(MemoryView)
    const error = wrapper.find('[data-test=error]')
    expect(error.text()).toContain('Memory backend unavailable')
    expect(error.text()).toContain('HTTP 503')
    expect(error.text()).toContain('trace-9')

    await error.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=error]').exists()).toBe(false)
    expect(wrapper.findAll('[data-test=archival-entry]')).toHaveLength(1)
  })

  it('does not leak a raw network error', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('ECONNREFUSED 127.0.0.1:1'))))
    const wrapper = await show(ToolsView)
    expect(wrapper.find('[data-test=error]').text()).toContain('Cannot reach the OryxOS server')
    expect(wrapper.text()).not.toContain('ECONNREFUSED')
  })
})

describe('read-only pages render real API data', () => {
  it('Profiles', async () => {
    stubApi({
      'GET /profiles': envelope([
        {
          name: 'alerts',
          description: 'Sends alerts',
          provider: { name: 'mock', model: 'm', temperature: 0.2 },
          tools: ['notify'],
          skills: [],
          mcpServers: [],
          scheduleCount: 2,
          sandboxDeclared: true
        }
      ])
    })
    const wrapper = await show(ProfilesView)
    const card = wrapper.find('[data-test=profile]').text()
    expect(card).toContain('alerts')
    expect(card).toContain('mock / m')
    expect(card).toContain('notify')
    expect(card).toContain('sandbox declared')
  })

  it('Tools lists parameters from the schema', async () => {
    stubApi({
      'GET /tools': envelope([
        {
          name: 'http_get',
          description: 'Fetch a URL',
          inputSchema: { type: 'object', properties: { url: { type: 'string' } }, required: ['url'] }
        }
      ])
    })
    const wrapper = await show(ToolsView)
    const text = wrapper.find('[data-test=tool]').text()
    expect(text).toContain('http_get')
    expect(text).toContain('Fetch a URL')
    expect(text).toContain('url')
    expect(text).toContain('yes')
  })

  it('System shows a failing health check without failing the page', async () => {
    stubApi({
      'GET /info': envelope({ application: 'oryxos', version: '1', stage: 'b4', authentication: 'none: trusted local use only', sessions: 3, profiles: 1, tools: 8, schedules: 0, uptimeSeconds: 5 }),
      'GET /health': { status: 503, body: { code: 503, message: 'Service unavailable' } }
    })
    const wrapper = await show(SystemView)
    expect(wrapper.find('[data-test=health]').text()).toBe('down')
    expect(wrapper.find('[data-test=info]').text()).toContain('oryxos 1 (b4)')
    expect(wrapper.text()).toContain('trusted local use only')
  })

  it('Sessions: the list is real and opening one shows its messages and tool audit', async () => {
    const calls = stubApi({
      'GET /sessions/web:u1:demo': envelope({ sessionId: 'web:u1:demo', profile: 'demo', channel: 'web', userId: 'u1', totalMessages: 2, truncated: false, messages: [{ role: 'USER', content: 'hi', toolCalls: [] }, { role: 'ASSISTANT', content: 'hello', toolCalls: [] }] }),
      'GET /tool-calls': envelope([{ id: 1, toolName: 'file_read', success: false, errorCategory: 'SandboxDenied', errorMessage: 'Path outside allowed READ roots', durationMs: 2, occurredAt: '2026-03-01T10:00:00Z' }]),
      'GET /sessions': envelope([{ sessionId: 'web:u1:demo', profileName: 'demo', channel: 'web', messageCount: 2, lastUserMessage: 'hi', createdAt: '2026-03-01T10:00:00Z', updatedAt: '2026-03-01T10:01:00Z' }])
    })
    const wrapper = await show(SessionsView, '/sessions/web:u1:demo')
    expect(wrapper.findAll('[data-test=session-row]')).toHaveLength(1)
    expect(wrapper.findAll('[data-test=message]').map((m) => m.text())).toEqual(['USER hi', 'ASSISTANT hello'])
    expect(wrapper.find('[data-test=session-tool-calls]').text()).toContain('SandboxDenied')
    expect(calls).toContain('GET /sessions/web:u1:demo')
  })
})

describe('Scheduler page', () => {
  const task = (over = {}) => ({
    taskId: 'ops.check',
    profileName: 'ops',
    cron: '0 0 9 * * *',
    zone: 'UTC',
    message: 'check things',
    enabled: false,
    running: false,
    nextRunAt: null,
    lastRunAt: null,
    lastStatus: null,
    runCount: 0,
    ...over
  })
  const execution = (over = {}) => ({
    id: 5,
    taskId: 'ops.check',
    sessionId: 'scheduler:scheduler:ops',
    trigger: 'manual',
    startedAt: '2026-03-01T10:00:00Z',
    success: true,
    errorMessage: null,
    durationMs: 40,
    ...over
  })
  const denied = {
    id: 3,
    toolName: 'file_write',
    success: false,
    errorCategory: 'SandboxDenied',
    errorMessage: 'Path outside allowed WRITE roots',
    durationMs: 1,
    occurredAt: '2026-03-01T10:00:00.010Z'
  }

  it('says a manual run is allowed although the task is disabled', async () => {
    stubApi({ 'GET /schedules': envelope([task()]) })
    const wrapper = await show(SchedulerView)
    await wrapper.find('[data-test=run]').trigger('click')
    expect(wrapper.find('[data-test=confirm]').text()).toContain('disabled')
    expect(wrapper.find('[data-test=confirm]').text()).toContain('still allowed')
  })

  it('does not run until confirmed, then separates technical status from tool audit', async () => {
    const calls = stubApi({
      'POST /schedules/ops.check/run': envelope(execution()),
      'GET /tool-calls': envelope([denied]),
      'GET /schedules/ops.check/executions': envelope([execution()]),
      'GET /schedules': envelope([task()])
    })
    const wrapper = await show(SchedulerView)
    await wrapper.find('[data-test=run]').trigger('click')
    expect(calls.some((c) => c.startsWith('POST'))).toBe(false)

    await wrapper.find('[data-test=confirm-run]').trigger('click')
    await flushPromises()
    const result = wrapper.find('[data-test=last-run]').text()
    expect(result).toContain('Technical execution')
    expect(result).toContain('finished without error')
    expect(result).toContain('Tool audit')
    expect(result).toContain('1 denied by the sandbox')
    expect(result).toContain('NOT carried out')
    expect(result).not.toMatch(/notification sent\b(?! or)/)
  })

  it('shows the 403 / 409 text from the server when a run cannot start', async () => {
    stubApi({
      'POST /schedules/ops.check/run': { status: 409, body: { code: 409, message: 'Task is already running' } },
      'GET /schedules': envelope([task({ enabled: true })])
    })
    const wrapper = await show(SchedulerView)
    await wrapper.find('[data-test=run]').trigger('click')
    await wrapper.find('[data-test=confirm-run]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=action-error]').text()).toContain('Task is already running')
  })

  it('enables and disables through PUT and reloads the list', async () => {
    let enabled = false
    const calls = stubApi({
      'PUT /schedules/ops.check': (init) => {
        enabled = JSON.parse(init.body).enabled
        return envelope(task({ enabled }))
      },
      'GET /schedules': () => envelope([task({ enabled })])
    })
    const wrapper = await show(SchedulerView)
    expect(wrapper.find('[data-test=toggle]').text()).toBe('Enable')
    await wrapper.find('[data-test=toggle]').trigger('click')
    await flushPromises()
    expect(calls).toContain('PUT /schedules/ops.check')
    expect(wrapper.find('[data-test=toggle]').text()).toBe('Disable')
  })

  it('history rows load the tool audit on demand', async () => {
    stubApi({
      'GET /schedules/ops.check/executions': envelope([execution({ success: false, errorMessage: 'Agent run failed (IllegalStateException)' })]),
      'GET /tool-calls': envelope([]),
      'GET /schedules': envelope([task({ enabled: true })])
    })
    const wrapper = await show(SchedulerView)
    await wrapper.find('[data-test=task-row]').trigger('click')
    await flushPromises()
    const row = wrapper.find('[data-test=history-row]').text()
    expect(row).toContain('failed')
    expect(row).toContain('Agent run failed (IllegalStateException)')
    await wrapper.find('[data-test=details]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=details-row]').text()).toContain('No tool calls recorded')
  })

  it('shows empty and error states', async () => {
    stubApi({ 'GET /schedules': envelope([]) })
    expect((await show(SchedulerView)).find('[data-test=empty]').text()).toContain('No scheduled tasks')
    vi.unstubAllGlobals()
    stubApi({ 'GET /schedules': { status: 500, body: { code: 500, message: 'Internal server error' } } })
    expect((await show(SchedulerView)).find('[data-test=error]').text()).toContain('Internal server error')
  })
})
