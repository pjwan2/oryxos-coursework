<script setup>
import { api } from '../api.js'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useResource } from '../composables/useResource.js'

const { state, data: profiles, error, reload } = useResource(() => api('/profiles'))
</script>

<template>
  <h1>Profiles</h1>
  <p class="page-intro">
    Agents loaded from the profile directory at startup. Read-only; webhook targets and sandbox
    paths are never shown here.
  </p>
  <AsyncState
    :state="state"
    :error="error"
    empty-text="No profiles are loaded. Add a YAML file to the profile directory and restart."
    @retry="reload"
  >
    <div class="grid">
      <div v-for="profile in profiles" :key="profile.name" class="card" data-test="profile">
        <div class="row" style="justify-content: space-between">
          <strong>{{ profile.name }}</strong>
          <StatusBadge :tone="profile.sandboxDeclared ? 'ok' : 'neutral'">
            {{ profile.sandboxDeclared ? 'sandbox declared' : 'no sandbox' }}
          </StatusBadge>
        </div>
        <p class="muted">{{ profile.description || 'No description.' }}</p>
        <dl class="facts">
          <dt>Provider</dt>
          <dd>{{ profile.provider.name }} / {{ profile.provider.model }}</dd>
          <dt>Temperature</dt>
          <dd>{{ profile.provider.temperature }}</dd>
          <dt>Schedules</dt>
          <dd>{{ profile.scheduleCount }}</dd>
          <dt>Tools</dt>
          <dd>
            <span v-for="tool in profile.tools" :key="tool" class="chip">{{ tool }}</span>
            <span v-if="!profile.tools.length" class="muted">none</span>
          </dd>
          <dt>Skills</dt>
          <dd>
            <span v-for="skill in profile.skills" :key="skill" class="chip">{{ skill }}</span>
            <span v-if="!profile.skills.length" class="muted">none</span>
          </dd>
          <dt>MCP servers</dt>
          <dd>
            <span v-for="server in profile.mcpServers" :key="server" class="chip">{{ server }}</span>
            <span v-if="!profile.mcpServers.length" class="muted">none</span>
          </dd>
        </dl>
      </div>
    </div>
  </AsyncState>
</template>
