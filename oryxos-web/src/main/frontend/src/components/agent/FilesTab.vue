<script setup>
import { computed, ref } from 'vue'
import { api, query } from '../../api.js'
import AsyncState from '../AsyncState.vue'
import { useResource } from '../../composables/useResource.js'

const props = defineProps({ agent: { type: Object, required: true } })
const emit = defineEmits(['saved'])

const tree = useResource(() => api('/workspace/tree'), { isEmpty: () => false })
const mine = computed(() =>
  (tree.data.value ?? []).filter((e) => e.path === props.agent.name || e.path.startsWith(props.agent.name + '/'))
)

const open = ref(null) // { path, content, editable }
const text = ref('')
const loadError = ref(null)
const saveError = ref(null)
const saved = ref(false)
const busy = ref(false)

async function openFile(path) {
  loadError.value = null
  saveError.value = null
  saved.value = false
  try {
    open.value = await api(`/workspace/file${query({ path })}`)
    text.value = open.value.content
  } catch (failure) {
    open.value = null
    loadError.value = failure.message
  }
}

async function save() {
  busy.value = true
  saveError.value = null
  saved.value = false
  try {
    open.value = await api('/workspace/file', {
      method: 'POST',
      body: { path: open.value.path, content: text.value }
    })
    saved.value = true
    emit('saved')
    await tree.reload()
  } catch (failure) {
    saveError.value = failure.message
  } finally {
    busy.value = false
  }
}

function depth(path) {
  return path.split('/').length - 1
}
</script>

<template>
  <p class="muted">
    Only this agent's own text files. Saving <span class="mono">AGENT.md</span> validates it and
    re-registers the agent; if it is invalid the previous version keeps running.
  </p>
  <div class="split">
    <div>
      <AsyncState
        :state="tree.state.value"
        :error="tree.error.value"
        empty-text="No files."
        @retry="tree.reload"
      >
        <ul class="files" data-test="tree">
          <li v-for="entry in mine" :key="entry.path" :style="{ paddingLeft: depth(entry.path) * 14 + 'px' }">
            <span v-if="entry.directory" class="muted mono">{{ entry.path.split('/').pop() }}/</span>
            <a v-else href="#" class="mono" data-test="file" @click.prevent="openFile(entry.path)">
              {{ entry.path.split('/').pop() }}
            </a>
          </li>
        </ul>
      </AsyncState>
    </div>
    <div>
      <div v-if="loadError" class="notice danger" role="alert" data-test="load-error">{{ loadError }}</div>
      <p v-if="!open && !loadError" class="muted">Select a file.</p>
      <template v-if="open">
        <div class="mono muted">{{ open.path }}</div>
        <textarea
          v-model="text"
          rows="22"
          class="mono"
          :readonly="!open.editable"
          :aria-label="'Content of ' + open.path"
          data-test="editor"
        ></textarea>
        <p v-if="!open.editable" class="muted">This file is read-only here.</p>
        <p v-else class="row">
          <button class="primary" :disabled="busy || text === open.content" data-test="save" @click="save">
            {{ busy ? 'Saving…' : 'Save' }}
          </button>
        </p>
        <div v-if="saveError" class="notice danger" role="alert" data-test="save-error">{{ saveError }}</div>
        <div v-if="saved" class="notice ok" data-test="saved">Saved.</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.split {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 16px;
}
.files {
  list-style: none;
  padding: 0;
  margin: 0;
}
textarea {
  width: 100%;
  font-family: var(--font-mono);
  color: var(--text);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
}
@media (max-width: 760px) {
  .split {
    grid-template-columns: 1fr;
  }
}
</style>
