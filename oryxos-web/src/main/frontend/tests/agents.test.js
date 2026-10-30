// Agent pages with a stubbed network layer. The stubs live only in this file: the views contain no
// data of their own and always call the real REST API.
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '../src/router.js'
import AgentDetailView from '../src/views/AgentDetailView.vue'
import AgentsView from '../src/views/AgentsView.vue'

const envelope = (data) => ({ code: 0, message: 'ok', data })

/** Routes "METHOD /path" (without /api/v1) to the first stub whose key is a prefix; most specific first. */
function stubApi(table) {
  const calls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url, init = {}) => {
      const key = `${init.method ?? 'GET'} ${decodeURIComponent(String(url)).replace('/api/v1', '')}`
      calls.push({ key, body: init.body ? JSON.parse(init.body) : undefined })
      const handler = Object.entries(table).find(([pattern]) => key.startsWith(pattern))?.[1]
      if (!handler) return new Response(JSON.stringify({ code: 404, message: `no stub ${key}` }), { status: 404 })
      const result = typeof handler === 'function' ? handler(init) : handler
      return new Response(JSON.stringify(result.body ?? result), { status: result.status ?? 200 })
    })
  )
  return calls
}

async function show(component, path) {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  const wrapper = mount(component, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

const agent = (over = {}) => ({
  name: 'daily',
  description: 'Reconciles payments',
  origin: 'agent-md',
  editable: true,
  provider: 'mock',
  model: 'mock-echo',
  temperature: 0.2,
  tools: ['file_read'],
  skills: [],
  schedules: [{ id: 'nightly', cron: '0 0 2 * * *', zone: 'UTC', message: 'go' }],
  resources: ['REFERENCE.md'],
  privateMemory: true,
  instructions: '# Daily\n\nCompare the ledger.',
  problem: null,
  ...over
})

afterEach(() => vi.unstubAllGlobals())

describe('Agents list', () => {
  it('shows real agents, the legacy marker and the directories that were not applied', async () => {
    stubApi({
      'GET /agents': envelope({
        agents: [agent(), agent({ name: 'old', editable: false, origin: 'legacy-yaml', privateMemory: false })],
        problems: { broken: 'agents/broken/AGENT.md: AGENT.md not found' }
      })
    })
    const { wrapper } = await show(AgentsView, '/agents')
    const cards = wrapper.findAll('[data-test=agent]')
    expect(cards).toHaveLength(2)
    expect(cards[0].text()).toContain('daily')
    expect(cards[0].text()).toContain('private')
    expect(cards[1].text()).toContain('legacy, read-only')
    expect(cards[1].text()).toContain('shared (global)')
    expect(wrapper.find('[data-test=problems]').text()).toContain('AGENT.md not found')
  })

  it('shows loading, empty and error states', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    expect((await show(AgentsView, '/agents')).wrapper.find('[data-test=loading]').exists()).toBe(true)
    stubApi({ 'GET /agents': envelope({ agents: [], problems: {} }) })
    expect((await show(AgentsView, '/agents')).wrapper.find('[data-test=empty]').text()).toContain('No agents')
    stubApi({ 'GET /agents': { status: 500, body: { code: 500, message: 'Internal server error' } } })
    expect((await show(AgentsView, '/agents')).wrapper.find('[data-test=error]').text()).toContain('Internal server error')
  })

  it('creates an agent through POST /agents and opens it; a refusal is shown, not swallowed', async () => {
    const calls = stubApi({
      'POST /agents': (init) => {
        const body = JSON.parse(init.body)
        if (body.name === 'taken') return { status: 409, body: { code: 409, message: "An agent or directory named 'taken' already exists" } }
        return { status: 201, body: envelope(agent({ name: body.name })) }
      },
      'GET /agents': envelope({ agents: [], problems: {} })
    })
    const { wrapper, router } = await show(AgentsView, '/agents')

    await wrapper.find('[data-test=create-name]').setValue('taken')
    await wrapper.find('[data-test=create-description]').setValue('x')
    await wrapper.find('[data-test=create-form]').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-test=create-error]').text()).toContain('already exists')

    await wrapper.find('[data-test=create-name]').setValue('fresh')
    await wrapper.find('[data-test=create-form]').trigger('submit')
    await flushPromises()
    const posts = calls.filter((c) => c.key === 'POST /agents')
    expect(posts.at(-1).body).toEqual({ name: 'fresh', description: 'x' })
    expect(router.currentRoute.value.path).toBe('/agents/fresh')
  })
})

describe('Agent detail', () => {
  async function detail(extra = {}, over = {}) {
    const calls = stubApi({ ...extra, 'GET /agents/daily': envelope(agent(over)) })
    const view = await show(AgentDetailView, '/agents/daily')
    return { ...view, calls }
  }

  it('overview shows the real definition and archives only after an explicit confirmation', async () => {
    const { wrapper, calls, router } = await detail({
      'DELETE /agents/daily': envelope({ name: 'daily', location: 'archive/daily-20260101T000000Z' })
    })
    const overview = wrapper.find('[data-test=overview]').text()
    expect(overview).toContain('mock / mock-echo')
    expect(overview).toContain('file_read')
    expect(overview).toContain('0 0 2 * * *')
    expect(wrapper.find('[data-test=instructions]').text()).toContain('Compare the ledger')

    await wrapper.find('[data-test=archive]').trigger('click')
    expect(calls.some((c) => c.key.startsWith('DELETE'))).toBe(false)
    expect(wrapper.find('[data-test=archive-confirm]').exists()).toBe(true)
    await wrapper.find('[data-test=archive-yes]').trigger('click')
    await flushPromises()
    expect(calls.some((c) => c.key === 'DELETE /agents/daily')).toBe(true)
    expect(router.currentRoute.value.path).toBe('/agents')
  })

  it('a legacy agent has no archive or generate controls', async () => {
    const { wrapper } = await detail({}, { editable: false, origin: 'legacy-yaml', instructions: null })
    expect(wrapper.find('[data-test=archive]').exists()).toBe(false)
    await wrapper.find('[data-test=tab-generate]').trigger('click')
    expect(wrapper.find('[data-test=generate-readonly]').exists()).toBe(true)
  })

  it('generate: the draft is shown, nothing is saved until confirmed, problems are readable', async () => {
    const draft = {
      files: [{ path: 'AGENT.md', content: '---\ndescription: d\n---\nBody' }],
      valid: false,
      problems: ['agents/daily/AGENT.md: tool "x" is not registered']
    }
    const { wrapper, calls } = await detail({
      'POST /agents/daily/generate-files': envelope(draft),
      'POST /agents/daily/files': envelope(agent())
    })
    await wrapper.find('[data-test=tab-generate]').trigger('click')
    await wrapper.find('[data-test=generate-description]').setValue('reconcile payments')
    await wrapper.find('[data-test=generate]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test=draft-status]').text()).toContain('NOT saved')
    expect(wrapper.find('[data-test=draft-status]').text()).toContain('is not registered')
    expect(wrapper.find('[data-test=draft-file]').element.value).toContain('Body')
    expect(calls.some((c) => c.key === 'POST /agents/daily/files')).toBe(false)

    await wrapper.find('[data-test=save-draft]').trigger('click')
    expect(calls.some((c) => c.key === 'POST /agents/daily/files')).toBe(false)
    await wrapper.find('[data-test=save-yes]').trigger('click')
    await flushPromises()
    const save = calls.find((c) => c.key === 'POST /agents/daily/files')
    expect(save.body.files[0].path).toBe('AGENT.md')
    expect(wrapper.find('[data-test=saved]').exists()).toBe(true)
  })

  it('generate: a missing provider is reported as the server says', async () => {
    const { wrapper } = await detail({
      'POST /agents/daily/generate-files': { status: 409, body: { code: 409, message: 'No usable provider/model is configured' } }
    })
    await wrapper.find('[data-test=tab-generate]').trigger('click')
    await wrapper.find('[data-test=generate-description]').setValue('x')
    await wrapper.find('[data-test=generate]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=generate-error]').text()).toContain('No usable provider')
  })

  it('files: lists only this agent, opens a file and shows the validation error of a refused save', async () => {
    const { wrapper } = await detail({
      'GET /workspace/tree': envelope([
        { path: 'daily', directory: true, size: 0 },
        { path: 'daily/AGENT.md', directory: false, size: 40 },
        { path: 'other', directory: true, size: 0 },
        { path: 'other/AGENT.md', directory: false, size: 40 }
      ]),
      'GET /workspace/file': envelope({ path: 'daily/AGENT.md', content: 'old', size: 3, editable: true }),
      'POST /workspace/file': { status: 400, body: { code: 400, message: 'agents/daily/AGENT.md: description is required and must be text' } }
    })
    await wrapper.find('[data-test=tab-files]').trigger('click')
    await flushPromises()
    const files = wrapper.findAll('[data-test=file]')
    expect(files).toHaveLength(1)
    await files[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=editor]').element.value).toBe('old')
    await wrapper.find('[data-test=editor]').setValue('new text')
    await wrapper.find('[data-test=save]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=save-error]').text()).toContain('description is required')
  })

  it('session: shows the stored messages and sends through the session endpoint', async () => {
    let messages = [{ role: 'USER', content: 'hi', toolCalls: [], toolReplies: [] }]
    const { wrapper, calls } = await detail({
      'POST /agents/daily/session/messages': () => {
        messages = [...messages, { role: 'ASSISTANT', content: 'Mock response: again', toolCalls: [], toolReplies: [] }]
        return envelope({ sessionId: 'admin:console:daily', reply: 'Mock response: again' })
      },
      'GET /agents/daily/session': () => envelope({ sessionId: 'admin:console:daily', agent: 'daily', exists: true, totalMessages: messages.length, truncated: false, messages })
    })
    await wrapper.find('[data-test=tab-session]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test=message]')).toHaveLength(1)
    await wrapper.find('[data-test=message-input]').setValue('again')
    await wrapper.find('[data-test=session-form]').trigger('submit')
    await flushPromises()
    expect(calls.find((c) => c.key === 'POST /agents/daily/session/messages').body).toEqual({ message: 'again' })
    expect(wrapper.findAll('[data-test=message]')).toHaveLength(2)
  })

  it('memory: says plainly when a legacy agent only has the shared global memory', async () => {
    const { wrapper } = await detail(
      {
        'GET /agents/daily/memory': envelope({
          agent: 'daily', privateMemory: false, shared: true, backend: 'markdown',
          core: [{ scope: 'CORE', content: 'shared fact', savedOn: '2026-03-01' }], archival: [], archivalLimit: 100
        })
      },
      { editable: false, privateMemory: false }
    )
    await wrapper.find('[data-test=tab-memory]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test=shared-note]').text()).toContain('every legacy profile shares')
    expect(wrapper.find('[data-test=core-entry]').text()).toContain('shared fact')
  })

  describe('scheduled runs', () => {
    const execution = (over = {}) => ({
      id: 5, taskId: 'daily.nightly', sessionId: 'scheduler:scheduler:daily', trigger: 'cron',
      startedAt: '2026-03-01T10:00:00Z', success: true, errorMessage: null, durationMs: 40,
      executionId: 'abc', ...over
    })
    const outcome = (over = {}) => ({
      executionId: 5, technicalStatus: 'SUCCESS', toolCalls: 2, toolSucceeded: 1, toolDenied: 1, toolFailed: 0,
      outcome: 'COMPLETED_WITH_TOOL_ISSUES', note: 'x', executionRef: 'abc', matchedBy: 'execution_id', caveat: null,
      calls: [
        { tool: 'http_get', success: false, category: 'SandboxDenied', reason: 'Host not allowed' },
        { tool: 'notify', success: true, category: null, reason: null }
      ],
      ...over
    })

    it('lists the recent runs of the agent and shows each tool call, a denial included, on demand', async () => {
      const { wrapper } = await detail({
        'GET /schedules/daily.nightly/executions/5/outcome': envelope(outcome()),
        'GET /schedules/daily.nightly/executions': envelope([execution()])
      })
      expect(wrapper.findAll('[data-test=run-row]')).toHaveLength(1)
      expect(wrapper.find('[data-test=run-row]').text()).toContain('finished')
      await wrapper.find('[data-test=details]').trigger('click')
      await flushPromises()
      const calls = wrapper.findAll('[data-test=call]').map((c) => c.text())
      expect(calls[0]).toContain('✗ http_get')
      expect(calls[0]).toContain('SandboxDenied')
      expect(calls[1]).toContain('✓ notify')
      expect(wrapper.find('[data-test=details-row]').text()).toContain('1 denied by the sandbox')
      expect(wrapper.find('[data-test=details-row]').text()).toContain('NOT carried out')
    })

    it('runs only after an explicit confirmation and then shows technical status and tool audit apart', async () => {
      const { wrapper, calls } = await detail({
        'POST /schedules/daily.nightly/run': envelope(execution({ trigger: 'manual' })),
        'GET /schedules/daily.nightly/executions/5/outcome': envelope(outcome()),
        'GET /schedules/daily.nightly/executions': envelope([execution({ trigger: 'manual' })])
      })
      await wrapper.find('[data-test=run]').trigger('click')
      expect(calls.some((c) => c.key.startsWith('POST /schedules'))).toBe(false)
      await wrapper.find('[data-test=run-yes]').trigger('click')
      await flushPromises()
      expect(calls.some((c) => c.key === 'POST /schedules/daily.nightly/run')).toBe(true)
      const result = wrapper.find('[data-test=last-run]').text()
      expect(result).toContain('Technical: Finished without error')
      expect(result).toContain('Tools: 1 denied by the sandbox, 1 succeeded')
    })

    it('an agent without a schedule says there is nothing to run', async () => {
      const { wrapper } = await detail({}, { schedules: [] })
      expect(wrapper.find('[data-test=no-schedules]').exists()).toBe(true)
      expect(wrapper.find('[data-test=run]').exists()).toBe(false)
    })

    it('a refused run shows the server message', async () => {
      const { wrapper } = await detail({
        'POST /schedules/daily.nightly/run': { status: 409, body: { code: 409, message: 'Task is already running' } },
        'GET /schedules/daily.nightly/executions': envelope([])
      })
      await wrapper.find('[data-test=run]').trigger('click')
      await wrapper.find('[data-test=run-yes]').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-test=run-error]').text()).toContain('Task is already running')
    })
  })
})
