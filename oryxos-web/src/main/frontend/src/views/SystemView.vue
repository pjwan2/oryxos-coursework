<script setup>
import { api } from '../api.js'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useResource } from '../composables/useResource.js'
import { formatTime, formatUptime } from '../format.js'

async function load() {
  const [info, health] = await Promise.all([
    api('/info'),
    // A 503 health answer is information for this page, not a page failure.
    api('/health').catch((failure) => ({
      status: 'down',
      checks: {},
      problem: failure.message
    }))
  ])
  return { info, health }
}

const { state, data, error, reload } = useResource(load)
</script>

<template>
  <h1>System</h1>
  <p class="page-intro">Runtime status. No configuration values or credentials are exposed here.</p>
  <AsyncState :state="state" :error="error" @retry="reload">
    <div v-if="data.info.authentication" class="notice">
      Authentication: {{ data.info.authentication }}. Do not expose this server beyond this machine.
    </div>
    <div class="card" style="margin-bottom: 12px">
      <div class="row">
        <strong>Health</strong>
        <StatusBadge :tone="data.health.status === 'ok' ? 'ok' : 'danger'" data-test="health">
          {{ data.health.status }}
        </StatusBadge>
        <span v-for="(value, name) in data.health.checks" :key="name" class="muted">
          {{ name }}: {{ value }}
        </span>
        <span v-if="data.health.problem" class="muted">{{ data.health.problem }}</span>
      </div>
    </div>
    <div class="card">
      <dl class="facts" data-test="info">
        <dt>Application</dt>
        <dd>{{ data.info.application }} {{ data.info.version }} ({{ data.info.stage }})</dd>
        <dt>Java</dt>
        <dd>{{ data.info.javaVersion }}</dd>
        <dt>Started</dt>
        <dd>{{ formatTime(data.info.startedAt) }}</dd>
        <dt>Uptime</dt>
        <dd>{{ formatUptime(data.info.uptimeSeconds) }}</dd>
        <dt>Bind address</dt>
        <dd>{{ data.info.bindAddress }}</dd>
        <dt>Database</dt>
        <dd>{{ data.info.database }}</dd>
        <dt>Memory backend</dt>
        <dd>{{ data.info.memoryBackend }}</dd>
        <dt>Scheduler</dt>
        <dd>{{ data.info.schedulerEnabled ? 'enabled' : 'disabled' }}</dd>
        <dt>Profiles</dt>
        <dd>{{ data.info.profiles }}</dd>
        <dt>Tools</dt>
        <dd>{{ data.info.tools }}</dd>
        <dt>Scheduled tasks</dt>
        <dd>{{ data.info.schedules }}</dd>
        <dt>Sessions</dt>
        <dd>{{ data.info.sessions }}</dd>
      </dl>
    </div>
  </AsyncState>
</template>
