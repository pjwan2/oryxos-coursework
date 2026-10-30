<script setup>
import { computed, ref } from 'vue'
import { api } from '../../api.js'
import { describeServerOutcome } from '../../scheduler.js'
import { formatDuration, formatTime } from '../../format.js'
import AsyncState from '../AsyncState.vue'
import StatusBadge from '../StatusBadge.vue'
import { useResource } from '../../composables/useResource.js'

// Scheduled runs of one agent: when they happened, whether the run finished, and what its tools
// did (technical status and tool audit are two separate facts, never one "success").
const props = defineProps({ agent: { type: Object, required: true } })

const taskIds = computed(() => props.agent.schedules.map((s) => `${props.agent.name}.${s.id}`))
const selected = ref(taskIds.value[0] ?? null)

const runs = useResource(
  () => api(`/schedules/${encodeURIComponent(selected.value)}/executions?limit=5`),
  { immediate: selected.value !== null }
)

const detail = ref({}) // execution id -> { state, outcome, error }
const confirming = ref(false)
const running = ref(false)
const runError = ref(null)
const lastRun = ref(null)

async function loadDetail(execution) {
  detail.value = { ...detail.value, [execution.id]: { state: 'loading' } }
  try {
    const outcome = await api(
      `/schedules/${encodeURIComponent(execution.taskId)}/executions/${execution.id}/outcome`
    )
    detail.value = {
      ...detail.value,
      [execution.id]: { state: 'ready', verdict: describeServerOutcome(execution, outcome), outcome }
    }
  } catch (failure) {
    detail.value = { ...detail.value, [execution.id]: { state: 'error', error: failure.message } }
  }
}

async function toggle(execution) {
  if (detail.value[execution.id]) {
    detail.value = { ...detail.value, [execution.id]: undefined }
  } else {
    await loadDetail(execution)
  }
}

async function runNow() {
  confirming.value = false
  running.value = true
  runError.value = null
  lastRun.value = null
  try {
    const execution = await api(`/schedules/${encodeURIComponent(selected.value)}/run`, {
      method: 'POST'
    })
    await loadDetail(execution)
    lastRun.value = execution
    await runs.reload()
  } catch (failure) {
    runError.value = `The run did not start: ${failure.message}`
  } finally {
    running.value = false
  }
}
</script>

<template>
  <h2>Scheduled runs</h2>
  <p v-if="!taskIds.length" class="muted" data-test="no-schedules">
    This agent declares no schedule, so there is nothing to run on a timer.
  </p>
  <template v-else>
    <p class="muted">
      A run executes the agent exactly as the timer would, including sending its notification to the
      configured target.
    </p>
    <div class="row">
      <select v-model="selected" aria-label="Schedule" data-test="schedule-select" @change="runs.reload()">
        <option v-for="id in taskIds" :key="id" :value="id">{{ id }}</option>
      </select>
      <button v-if="!confirming" :disabled="running" data-test="run" @click="confirming = true">
        Run now…
      </button>
      <template v-else>
        <button class="primary" :disabled="running" data-test="run-yes" @click="runNow">
          {{ running ? 'Running…' : 'Yes, run it now' }}
        </button>
        <button :disabled="running" @click="confirming = false">Cancel</button>
      </template>
    </div>
    <div v-if="runError" class="notice danger" role="alert" data-test="run-error">{{ runError }}</div>
    <div
      v-if="lastRun && detail[lastRun.id]?.verdict"
      :class="['notice', detail[lastRun.id].verdict.counts.denied + detail[lastRun.id].verdict.counts.failed ? 'danger' : 'ok']"
      data-test="last-run"
    >
      Technical: {{ detail[lastRun.id].verdict.technical.label }}. Tools: {{ detail[lastRun.id].verdict.audit.label }}.
      <div v-if="detail[lastRun.id].verdict.caution">{{ detail[lastRun.id].verdict.caution }}</div>
    </div>

    <AsyncState
      :state="runs.state.value"
      :error="runs.error.value"
      empty-text="No run yet."
      @retry="runs.reload"
    >
      <table data-test="runs">
        <thead>
          <tr>
            <th>Started</th>
            <th>Trigger</th>
            <th>Technical</th>
            <th>Took</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="run in runs.data.value" :key="run.id">
            <tr data-test="run-row">
              <td class="nowrap">{{ formatTime(run.startedAt) }}</td>
              <td>{{ run.trigger }}</td>
              <td>
                <StatusBadge :tone="run.success ? 'ok' : 'danger'">
                  {{ run.success ? 'finished' : 'failed' }}
                </StatusBadge>
                <span v-if="run.errorMessage" class="muted">{{ run.errorMessage }}</span>
              </td>
              <td>{{ formatDuration(run.durationMs) }}</td>
              <td><button data-test="details" @click="toggle(run)">Tool calls</button></td>
            </tr>
            <tr v-if="detail[run.id]" data-test="details-row">
              <td colspan="5">
                <span v-if="detail[run.id].state === 'loading'" class="muted">Loading…</span>
                <span v-else-if="detail[run.id].state === 'error'" class="muted">
                  Could not be loaded: {{ detail[run.id].error }}
                </span>
                <template v-else>
                  <StatusBadge :tone="detail[run.id].verdict.audit.tone">
                    {{ detail[run.id].verdict.audit.label }}
                  </StatusBadge>
                  <div v-for="(call, i) in detail[run.id].outcome.calls" :key="i" class="mono" data-test="call">
                    {{ call.success ? '✓' : '✗' }} {{ call.tool }}
                    <span v-if="call.category" class="muted">{{ call.category }}</span>
                    <span v-if="call.reason" class="muted">— {{ call.reason }}</span>
                  </div>
                  <div v-if="detail[run.id].verdict.caution" class="notice danger" style="margin: 8px 0 0">
                    {{ detail[run.id].verdict.caution }}
                  </div>
                  <div v-if="detail[run.id].verdict.caveat" class="muted">
                    {{ detail[run.id].verdict.caveat }}
                  </div>
                </template>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </AsyncState>
  </template>
</template>

<style scoped>
select {
  font: inherit;
  color: var(--text);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 5px 10px;
}
</style>
