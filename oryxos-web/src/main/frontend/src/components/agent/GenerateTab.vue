<script setup>
import { ref } from 'vue'
import { api } from '../../api.js'

const props = defineProps({ agent: { type: Object, required: true } })
const emit = defineEmits(['saved'])

const description = ref('')
const generating = ref(false)
const error = ref(null)
const draft = ref(null) // { files: [{path, content}], valid, problems }
const confirming = ref(false)
const saving = ref(false)
const saveError = ref(null)
const saved = ref(false)

async function generate() {
  generating.value = true
  error.value = null
  draft.value = null
  saved.value = false
  confirming.value = false
  try {
    draft.value = await api(`/agents/${encodeURIComponent(props.agent.name)}/generate-files`, {
      method: 'POST',
      body: { description: description.value }
    })
  } catch (failure) {
    error.value = failure.message
  } finally {
    generating.value = false
  }
}

async function save() {
  saving.value = true
  saveError.value = null
  try {
    await api(`/agents/${encodeURIComponent(props.agent.name)}/files`, {
      method: 'POST',
      body: { files: draft.value.files }
    })
    saved.value = true
    confirming.value = false
    emit('saved')
  } catch (failure) {
    saveError.value = failure.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="!agent.editable" class="notice" data-test="generate-readonly">
    A legacy profile cannot be changed from here.
  </div>
  <template v-else>
    <p class="muted">
      Describe what the agent should do. The agent's own model proposes files; you read, edit and
      confirm them. Nothing is saved or run until you confirm, and scripts are never drafted.
    </p>
    <textarea
      v-model="description"
      rows="4"
      aria-label="What the agent should do"
      placeholder="Reconcile yesterday's payments against the bank file and report differences."
      data-test="generate-description"
    ></textarea>
    <p class="row">
      <button
        class="primary"
        :disabled="generating || !description.trim()"
        data-test="generate"
        @click="generate"
      >
        {{ generating ? 'Asking the model…' : 'Generate draft' }}
      </button>
    </p>
    <div v-if="error" class="notice danger" role="alert" data-test="generate-error">
      {{ error }}
    </div>

    <template v-if="draft">
      <div
        :class="['notice', draft.valid ? 'ok' : 'danger']"
        data-test="draft-status"
      >
        {{
          draft.valid
            ? 'The draft passes validation. It is NOT saved yet.'
            : 'The draft has problems and cannot be saved as it is. It is NOT saved.'
        }}
        <ul v-if="draft.problems.length" style="margin: 6px 0 0">
          <li v-for="p in draft.problems" :key="p">{{ p }}</li>
        </ul>
      </div>
      <div v-for="file in draft.files" :key="file.path" style="margin-bottom: 12px">
        <div class="mono muted">{{ file.path }}</div>
        <textarea
          v-model="file.content"
          rows="12"
          class="mono"
          :aria-label="'Draft of ' + file.path"
          data-test="draft-file"
        ></textarea>
      </div>
      <div v-if="draft.files.length">
        <button v-if="!confirming" data-test="save-draft" @click="confirming = true">
          Save and apply…
        </button>
        <div v-else class="notice" data-test="save-confirm">
          Save {{ draft.files.length }} file(s) to <strong>{{ agent.name }}</strong> and make them
          take effect now? The current files are replaced (and restored if validation fails).
          <div class="row" style="margin-top: 8px">
            <button class="primary" :disabled="saving" data-test="save-yes" @click="save">
              {{ saving ? 'Saving…' : 'Yes, save' }}
            </button>
            <button :disabled="saving" @click="confirming = false">Cancel</button>
          </div>
        </div>
      </div>
      <div v-if="saveError" class="notice danger" role="alert" data-test="save-error">
        {{ saveError }}
      </div>
      <div v-if="saved" class="notice ok" data-test="saved">Saved. The agent now uses these files.</div>
    </template>
  </template>
</template>

<style scoped>
textarea {
  width: 100%;
  font: inherit;
  color: var(--text);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
}
</style>
