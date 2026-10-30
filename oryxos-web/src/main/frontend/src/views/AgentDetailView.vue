<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import FilesTab from '../components/agent/FilesTab.vue'
import GenerateTab from '../components/agent/GenerateTab.vue'
import MemoryTab from '../components/agent/MemoryTab.vue'
import OverviewTab from '../components/agent/OverviewTab.vue'
import SessionTab from '../components/agent/SessionTab.vue'
import { useResource } from '../composables/useResource.js'

const route = useRoute()
const router = useRouter()
const name = computed(() => String(route.params.name))

const agent = useResource(() => api(`/agents/${encodeURIComponent(name.value)}`), {
  immediate: false,
  isEmpty: () => false
})
watch(name, () => agent.reload(), { immediate: true })

/** Re-reads the agent without replacing the page by "Loading…", so an open tab keeps its state. */
async function refresh() {
  try {
    agent.data.value = await api(`/agents/${encodeURIComponent(name.value)}`)
  } catch (failure) {
    agent.error.value = failure
    agent.state.value = 'error'
  }
}

const tabs = [
  { id: 'overview', label: 'Overview' },
  { id: 'generate', label: 'Generate' },
  { id: 'files', label: 'Files' },
  { id: 'session', label: 'Session' },
  { id: 'memory', label: 'Memory' }
]
const tab = ref('overview')

function archived() {
  router.push('/agents')
}
</script>

<template>
  <p><router-link to="/agents">← Agents</router-link></p>
  <AsyncState :state="agent.state.value" :error="agent.error.value" @retry="agent.reload">
    <template v-if="agent.data.value">
      <div class="row" style="justify-content: space-between">
        <h1>{{ agent.data.value.name }}</h1>
        <StatusBadge :tone="agent.data.value.editable ? 'ok' : 'neutral'">
          {{ agent.data.value.editable ? 'AGENT.md' : 'legacy, read-only' }}
        </StatusBadge>
      </div>
      <p class="page-intro">{{ agent.data.value.description }}</p>
      <div v-if="agent.data.value.problem" class="notice danger" data-test="agent-problem">
        {{ agent.data.value.problem }}
      </div>

      <div class="tabs" role="tablist">
        <button
          v-for="t in tabs"
          :key="t.id"
          role="tab"
          :aria-selected="tab === t.id"
          :class="{ active: tab === t.id }"
          :data-test="'tab-' + t.id"
          @click="tab = t.id"
        >
          {{ t.label }}
        </button>
      </div>

      <OverviewTab
        v-if="tab === 'overview'"
        :agent="agent.data.value"
        @archived="archived"
      />
      <GenerateTab
        v-else-if="tab === 'generate'"
        :agent="agent.data.value"
        @saved="refresh"
      />
      <FilesTab v-else-if="tab === 'files'" :agent="agent.data.value" @saved="refresh" />
      <SessionTab v-else-if="tab === 'session'" :agent="agent.data.value" />
      <MemoryTab v-else :agent="agent.data.value" />
    </template>
  </AsyncState>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border);
  margin: 12px 0 16px;
}
.tabs button {
  border: none;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  background: none;
  color: var(--muted);
}
.tabs button.active {
  color: var(--text);
  border-bottom-color: var(--accent);
}
</style>
