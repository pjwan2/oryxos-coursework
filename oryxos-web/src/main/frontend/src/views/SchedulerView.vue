<script setup>
import { ref } from 'vue'
import { api, query } from '../api.js'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useResource } from '../composables/useResource.js'
import { formatDuration, formatTime } from '../format.js'
import { describeOutcome, executionWindow } from '../scheduler.js'

const tasks = useResource(() => api('/schedules'))

const selectedId = ref(null)
const history = useResource(
  () => api(`/schedules/${encodeURIComponent(selectedId.value)}/executions?limit=50`),
  { immediate: false }
)

const busy = ref(null) // task id with a request in flight
const actionError = ref(null)
const confirming = ref(null) // task id waiting for the "run anyway" confirmation
const lastRun = ref(null) // { taskId, execution, outcome, auditError }
const expanded = ref({}) // execution id -> { state, outcome, error }

function select(taskId) {
  selectedId.value = taskId
  expanded.value = {}
  history.reload()
}

function askToRun(task) {
  actionError.value = null
  confirming.value = task.taskId
}

async function loadOutcome(execution) {
  const window = executionWindow(execution)
  const calls = await api(
    `/tool-calls${query({ sessionId: execution.sessionId, since: window.since, until: window.until, limit: 100 })}`
  )
  return describeOutcome(execution, calls.slice().reverse())
}

async function runNow(task) {
  confirming.value = null
  busy.value = task.taskId
  actionError.value = null
  lastRun.value = null
  try {
    const execution = await api(`/schedules/${encodeURIComponent(task.taskId)}/run`, {
      method: 'POST'
    })
    let outcome = null
    let auditError = null
    try {
      outcome = await loadOutcome(execution)
    } catch (failure) {
      auditError = failure.message
    }
    lastRun.value = { taskId: task.taskId, execution, outcome, auditError }
    await tasks.reload()
    if (selectedId.value === task.taskId) await history.reload()
  } catch (failure) {
    actionError.value = `Run failed to start: ${failure.message}`
  } finally {
    busy.value = null
  }
}

async function toggle(task) {
  busy.value = task.taskId
  actionError.value = null
  try {
    await api(`/schedules/${encodeURIComponent(task.taskId)}`, {
      method: 'PUT',
      body: { enabled: !task.enabled }
    })
    await tasks.reload()
  } catch (failure) {
    actionError.value = `Could not change ${task.taskId}: ${failure.message}`
  } finally {
    busy.value = null
  }
}

async function toggleDetails(execution) {
  const key = execution.id
  if (expanded.value[key]) {
    expanded.value = { ...expanded.value, [key]: undefined }
    return
  }
  expanded.value = { ...expanded.value, [key]: { state: 'loading' } }
  try {
    const outcome = await loadOutcome(execution)
    expanded.value = { ...expanded.value, [key]: { state: 'ready', outcome } }
  } catch (failure) {
    expanded.value = { ...expanded.value, [key]: { state: 'error', error: failure.message } }
  }
}
</script>

<template>
  <h1>Scheduler</h1>
  <p class="page-intro">
    Scheduled agent tasks. Cron runs only while a task is enabled. Changes need local access.
  </p>

  <div v-if="actionError" class="notice danger" role="alert" data-test="action-error">
    {{ actionError }}
  </div>

  <div v-if="lastRun" class="card" style="margin-bottom: 16px" data-test="last-run">
    <strong>Result of the manual run of {{ lastRun.taskId }}</strong>
    <dl class="facts" style="margin-top: 8px">
      <dt>Technical execution</dt>
      <dd>
        <StatusBadge :tone="lastRun.execution.success ? 'ok' : 'danger'">
          {{ lastRun.execution.success ? 'finished without error' : 'failed' }}
        </StatusBadge>
        <span v-if="lastRun.execution.errorMessage" class="muted">
          {{ lastRun.execution.errorMessage }}
        </span>
        took {{ formatDuration(lastRun.execution.durationMs) }}
      </dd>
      <dt>Tool audit</dt>
      <dd v-if="lastRun.outcome">
        <StatusBadge :tone="lastRun.outcome.audit.tone">{{ lastRun.outcome.audit.label }}</StatusBadge>
      </dd>
      <dd v-else class="muted">Could not be loaded: {{ lastRun.auditError }}</dd>
    </dl>
    <div v-if="lastRun.outcome?.caution" class="notice danger" style="margin: 8px 0 0">
      {{ lastRun.outcome.caution }}
    </div>
  </div>

  <AsyncState
    :state="tasks.state.value"
    :error="tasks.error.value"
    empty-text="No scheduled tasks. Add a schedules entry to a profile and restart."
    @retry="tasks.reload"
  >
    <table>
      <thead>
        <tr>
          <th>Task</th>
          <th>Cron / zone</th>
          <th>State</th>
          <th>Next run</th>
          <th>Last</th>
          <th>Runs</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="task in tasks.data.value" :key="task.taskId">
          <tr
            class="clickable"
            :class="{ selected: task.taskId === selectedId }"
            data-test="task-row"
            @click="select(task.taskId)"
          >
            <td>
              <div class="mono">{{ task.taskId }}</div>
              <div class="muted wrap">{{ task.message }}</div>
            </td>
            <td class="mono nowrap">{{ task.cron }}<br />{{ task.zone }}</td>
            <td>
              <StatusBadge :tone="task.enabled ? 'ok' : 'warn'">
                {{ task.enabled ? 'enabled' : 'disabled' }}
              </StatusBadge>
              <StatusBadge v-if="task.running" tone="warn">running</StatusBadge>
            </td>
            <td class="nowrap">{{ task.enabled ? formatTime(task.nextRunAt) : '—' }}</td>
            <td>
              <StatusBadge
                v-if="task.lastStatus"
                :tone="task.lastStatus === 'SUCCESS' ? 'ok' : 'danger'"
              >
                {{ task.lastStatus }}
              </StatusBadge>
              <span v-else class="muted">never</span>
              <div class="muted">{{ formatTime(task.lastRunAt) }}</div>
            </td>
            <td>{{ task.runCount }}</td>
            <td @click.stop>
              <div class="row">
                <button :disabled="busy === task.taskId" data-test="run" @click="askToRun(task)">
                  Run now
                </button>
                <button :disabled="busy === task.taskId" data-test="toggle" @click="toggle(task)">
                  {{ task.enabled ? 'Disable' : 'Enable' }}
                </button>
              </div>
              <div
                v-if="confirming === task.taskId"
                class="notice"
                style="margin: 8px 0 0"
                data-test="confirm"
              >
                <template v-if="!task.enabled">
                  This task is <strong>disabled</strong>, but a manual run is still allowed and
                  will execute the agent now.
                </template>
                <template v-else>This runs the agent now, in addition to its schedule.</template>
                <div class="row" style="margin-top: 6px">
                  <button class="primary" data-test="confirm-run" @click="runNow(task)">
                    Run
                  </button>
                  <button @click="confirming = null">Cancel</button>
                </div>
              </div>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </AsyncState>

  <section v-if="selectedId">
    <h2>
      Execution history — <span class="mono">{{ selectedId }}</span>
    </h2>
    <AsyncState
      :state="history.state.value"
      :error="history.error.value"
      empty-text="This task has not run yet."
      @retry="history.reload"
    >
      <table data-test="history">
        <thead>
          <tr>
            <th>#</th>
            <th>Started</th>
            <th>Trigger</th>
            <th>Technical execution</th>
            <th>Took</th>
            <th>Tool audit</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="e in history.data.value" :key="e.id">
            <tr data-test="history-row">
              <td>{{ e.id }}</td>
              <td>{{ formatTime(e.startedAt) }}</td>
              <td>{{ e.trigger }}</td>
              <td>
                <StatusBadge :tone="e.success ? 'ok' : 'danger'">
                  {{ e.success ? 'finished' : 'failed' }}
                </StatusBadge>
                <span v-if="e.errorMessage" class="muted"> {{ e.errorMessage }}</span>
              </td>
              <td>{{ formatDuration(e.durationMs) }}</td>
              <td>
                <button data-test="details" @click="toggleDetails(e)">
                  {{ expanded[e.id] ? 'Hide' : 'Show' }}
                </button>
              </td>
            </tr>
            <tr v-if="expanded[e.id]" data-test="details-row">
              <td colspan="6">
                <span v-if="expanded[e.id].state === 'loading'" class="muted">Loading…</span>
                <span v-else-if="expanded[e.id].state === 'error'" class="muted">
                  Tool audit could not be loaded: {{ expanded[e.id].error }}
                </span>
                <template v-else>
                  <StatusBadge :tone="expanded[e.id].outcome.audit.tone">
                    {{ expanded[e.id].outcome.audit.label }}
                  </StatusBadge>
                  <span class="muted">
                    matched by session and time window of this run
                  </span>
                  <div v-if="expanded[e.id].outcome.caution" class="notice danger" style="margin: 8px 0 0">
                    {{ expanded[e.id].outcome.caution }}
                  </div>
                </template>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </AsyncState>
  </section>
</template>
