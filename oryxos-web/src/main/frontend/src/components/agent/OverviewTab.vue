<script setup>
import { ref } from 'vue'
import { api } from '../../api.js'

const props = defineProps({ agent: { type: Object, required: true } })
const emit = defineEmits(['archived'])

const confirming = ref(false)
const busy = ref(false)
const error = ref(null)
const done = ref(null)

async function archive() {
  busy.value = true
  error.value = null
  try {
    done.value = await api(`/agents/${encodeURIComponent(props.agent.name)}`, { method: 'DELETE' })
    confirming.value = false
    emit('archived', done.value)
  } catch (failure) {
    error.value = failure.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <dl class="facts card" data-test="overview">
    <dt>Provider</dt>
    <dd>{{ agent.provider }} / {{ agent.model }} (temperature {{ agent.temperature }})</dd>
    <dt>Tools</dt>
    <dd>
      <span v-for="tool in agent.tools" :key="tool" class="chip">{{ tool }}</span>
      <span v-if="!agent.tools.length" class="muted">none</span>
    </dd>
    <dt>Schedules</dt>
    <dd>
      <div v-for="s in agent.schedules" :key="s.id" class="mono" data-test="schedule">
        {{ s.id }}: {{ s.cron }} ({{ s.zone }}) — {{ s.message }}
      </div>
      <span v-if="!agent.schedules.length" class="muted">none</span>
    </dd>
    <dt>Files</dt>
    <dd>
      <span v-for="file in agent.resources" :key="file" class="chip">{{ file }}</span>
      <span v-if="!agent.resources.length" class="muted">only AGENT.md</span>
    </dd>
    <dt>Memory</dt>
    <dd>
      {{
        agent.privateMemory
          ? 'private to this agent'
          : 'shared global memory (legacy profile; not isolated)'
      }}
    </dd>
  </dl>

  <h2>Instructions</h2>
  <pre
    v-if="agent.instructions"
    class="card"
    style="white-space: pre-wrap"
    data-test="instructions"
    >{{ agent.instructions }}</pre
  >
  <p v-else class="muted">Legacy profiles keep their prompt in the YAML file.</p>

  <template v-if="agent.editable">
    <h2>Archive</h2>
    <p class="muted">
      Stops the agent and moves its directory, including its memory, to the archive. Nothing is
      deleted; sessions and audit history stay.
    </p>
    <button v-if="!confirming" data-test="archive" @click="confirming = true">Archive…</button>
    <div v-else class="notice danger" data-test="archive-confirm">
      Archive <strong>{{ agent.name }}</strong
      >? Its schedules stop and it can no longer be called.
      <div class="row" style="margin-top: 8px">
        <button class="primary" :disabled="busy" data-test="archive-yes" @click="archive">
          {{ busy ? 'Archiving…' : 'Yes, archive it' }}
        </button>
        <button :disabled="busy" @click="confirming = false">Cancel</button>
      </div>
    </div>
    <div v-if="error" class="notice danger" role="alert" data-test="archive-error">
      {{ error }}
    </div>
  </template>
</template>
