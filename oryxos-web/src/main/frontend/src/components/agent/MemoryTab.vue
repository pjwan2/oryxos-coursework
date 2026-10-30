<script setup>
import { api } from '../../api.js'
import AsyncState from '../AsyncState.vue'
import StatusBadge from '../StatusBadge.vue'
import { useResource } from '../../composables/useResource.js'

const props = defineProps({ agent: { type: Object, required: true } })

const memory = useResource(() => api(`/agents/${encodeURIComponent(props.agent.name)}/memory`), {
  isEmpty: () => false
})
</script>

<template>
  <AsyncState :state="memory.state.value" :error="memory.error.value" @retry="memory.reload">
    <template v-if="memory.data.value">
      <p>
        <StatusBadge :tone="memory.data.value.privateMemory ? 'ok' : 'warn'">
          {{ memory.data.value.privateMemory ? 'private to this agent' : 'shared global memory' }}
        </StatusBadge>
        <StatusBadge>{{ memory.data.value.backend }}</StatusBadge>
      </p>
      <div v-if="memory.data.value.shared" class="notice" data-test="shared-note">
        This legacy profile has no memory of its own: it reads and writes the global memory that
        every legacy profile shares. Define it as an AGENT.md agent to give it a private one.
      </div>
      <h2>Core ({{ memory.data.value.core.length }})</h2>
      <div v-if="!memory.data.value.core.length" class="card muted">No core entries.</div>
      <div v-for="(e, i) in memory.data.value.core" :key="'c' + i" class="message" data-test="core-entry">
        <div class="role">{{ e.savedOn ?? 'undated' }}</div>
        {{ e.content }}
      </div>
      <h2>Archival (latest {{ memory.data.value.archival.length }})</h2>
      <div v-if="!memory.data.value.archival.length" class="card muted">No archival entries.</div>
      <div
        v-for="(e, i) in memory.data.value.archival"
        :key="'a' + i"
        class="message"
        data-test="archival-entry"
      >
        <div class="role">{{ e.savedOn ?? 'undated' }}</div>
        {{ e.content }}
      </div>
    </template>
  </AsyncState>
</template>
