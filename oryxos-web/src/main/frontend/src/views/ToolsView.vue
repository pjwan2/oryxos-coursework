<script setup>
import { api } from '../api.js'
import AsyncState from '../components/AsyncState.vue'
import { useResource } from '../composables/useResource.js'
import { schemaParameters } from '../format.js'

const { state, data: tools, error, reload } = useResource(() => api('/tools'))
</script>

<template>
  <h1>Tools</h1>
  <p class="page-intro">
    Everything registered with the runtime. Which agent may call which tool is decided by each
    profile allow-list and its sandbox.
  </p>
  <AsyncState :state="state" :error="error" empty-text="No tools are registered." @retry="reload">
    <div
      v-for="tool in tools"
      :key="tool.name"
      class="card"
      style="margin-bottom: 12px"
      data-test="tool"
    >
      <div class="mono">
        <strong>{{ tool.name }}</strong>
      </div>
      <p class="muted" style="margin: 4px 0 8px">{{ tool.description }}</p>
      <table v-if="schemaParameters(tool.inputSchema).length">
        <thead>
          <tr>
            <th>Parameter</th>
            <th>Type</th>
            <th>Required</th>
            <th>Notes</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="param in schemaParameters(tool.inputSchema)" :key="param.name">
            <td class="mono">{{ param.name }}</td>
            <td class="mono">{{ param.type }}</td>
            <td>{{ param.required ? 'yes' : 'no' }}</td>
            <td>
              {{ param.description }}
              <span v-if="param.allowed" class="muted">one of: {{ param.allowed }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">This tool takes no declared parameters.</p>
      <details>
        <summary>Raw JSON Schema</summary>
        <pre>{{ JSON.stringify(tool.inputSchema, null, 2) }}</pre>
      </details>
    </div>
  </AsyncState>
</template>
