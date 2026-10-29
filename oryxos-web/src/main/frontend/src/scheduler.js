// How to read a finished scheduled run. Two different facts are kept apart on purpose:
//
//   * technical execution - did `AgentService.process` finish or throw? (task_executions.success)
//   * tool audit          - what happened to each tool call the agent made? (tool_invocations)
//
// A run can finish "successfully" while the sandbox refused every action it tried. That is NOT a
// notification sent or a business operation done, so the two are reported separately.

export const SANDBOX_DENIED = 'SandboxDenied'

const SLACK_MS = 1000

/** Time window (ISO strings) in which this run's tool calls were recorded. */
export function executionWindow(execution) {
  const start = new Date(execution.startedAt).getTime()
  return {
    since: new Date(start).toISOString(),
    until: new Date(start + (execution.durationMs ?? 0) + SLACK_MS).toISOString()
  }
}

export function summarizeToolCalls(calls) {
  const summary = { total: calls.length, succeeded: 0, denied: 0, failed: 0 }
  for (const call of calls) {
    if (call.success) summary.succeeded += 1
    else if (call.errorCategory === SANDBOX_DENIED) summary.denied += 1
    else summary.failed += 1
  }
  return summary
}

export function describeOutcome(execution, calls) {
  const technical = execution.success
    ? { tone: 'ok', label: 'Finished without error', detail: null }
    : { tone: 'danger', label: 'Failed', detail: execution.errorMessage ?? null }

  const counts = summarizeToolCalls(calls)
  let audit
  if (counts.total === 0) {
    audit = { tone: 'neutral', label: 'No tool calls recorded in this run' }
  } else if (counts.denied + counts.failed === 0) {
    audit = { tone: 'ok', label: `${counts.succeeded} tool call(s) succeeded` }
  } else {
    const parts = []
    if (counts.denied) parts.push(`${counts.denied} denied by the sandbox`)
    if (counts.failed) parts.push(`${counts.failed} failed`)
    if (counts.succeeded) parts.push(`${counts.succeeded} succeeded`)
    audit = { tone: 'danger', label: parts.join(', ') }
  }

  const caution =
    counts.denied + counts.failed > 0
      ? execution.success
        ? 'The run finished, but at least one tool action was NOT carried out. ' +
          'A finished run is not proof that a notification was sent or an operation was performed.'
        : 'At least one tool action was not carried out.'
      : null
  return { technical, audit, counts, caution }
}
