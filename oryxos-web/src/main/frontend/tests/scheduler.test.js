import { describe, expect, it } from 'vitest'
import { describeOutcome, executionWindow, summarizeToolCalls } from '../src/scheduler.js'

const run = (over = {}) => ({
  id: 1,
  taskId: 'ops.check',
  sessionId: 'scheduler:scheduler:ops',
  trigger: 'manual',
  startedAt: '2026-03-01T10:00:00.123456Z',
  success: true,
  errorMessage: null,
  durationMs: 250,
  ...over
})

const call = (over = {}) => ({
  id: 1,
  toolName: 'file_write',
  success: true,
  errorCategory: null,
  errorMessage: null,
  ...over
})

describe('executionWindow', () => {
  it('starts at the run start (never later) and ends after the duration plus slack', () => {
    const { since, until } = executionWindow(run())
    expect(since).toBe('2026-03-01T10:00:00.123Z')
    expect(until).toBe('2026-03-01T10:00:01.373Z')
  })
})

describe('summarizeToolCalls', () => {
  it('separates sandbox refusals from other failures and successes', () => {
    const counts = summarizeToolCalls([
      call(),
      call({ success: false, errorCategory: 'SandboxDenied' }),
      call({ success: false, errorCategory: 'SandboxDenied' }),
      call({ success: false, errorCategory: 'ToolReturnedFailure' })
    ])
    expect(counts).toEqual({ total: 4, succeeded: 1, denied: 2, failed: 1 })
  })
})

describe('describeOutcome', () => {
  it('reports a clean run as clean on both axes', () => {
    const o = describeOutcome(run(), [call()])
    expect(o.technical.tone).toBe('ok')
    expect(o.audit.tone).toBe('ok')
    expect(o.caution).toBeNull()
  })

  it('never presents a sandbox refusal as success even when the run finished', () => {
    const o = describeOutcome(run({ success: true }), [
      call({ success: false, errorCategory: 'SandboxDenied' })
    ])
    expect(o.technical.label).toBe('Finished without error')
    expect(o.audit.tone).toBe('danger')
    expect(o.audit.label).toContain('denied by the sandbox')
    expect(o.caution).toMatch(/NOT carried out/)
    expect(o.caution).toMatch(/not proof/)
  })

  it('keeps a technical failure and its safe reason', () => {
    const o = describeOutcome(
      run({ success: false, errorMessage: 'Agent run failed (ModelTimeoutException)' }),
      []
    )
    expect(o.technical.tone).toBe('danger')
    expect(o.technical.detail).toContain('ModelTimeoutException')
    expect(o.audit.label).toBe('No tool calls recorded in this run')
  })

  it('mixes counts in the audit label', () => {
    const o = describeOutcome(run(), [
      call(),
      call({ success: false, errorCategory: 'ToolReturnedFailure' })
    ])
    expect(o.audit.label).toBe('1 failed, 1 succeeded')
  })
})
