<script setup>
// One place for the three non-happy states every page needs: loading, empty and error.
defineProps({
  state: { type: String, required: true },
  error: { type: Object, default: null },
  emptyText: { type: String, default: 'Nothing here yet.' }
})
defineEmits(['retry'])
</script>

<template>
  <div v-if="state === 'loading'" class="card muted" role="status" data-test="loading">
    Loading…
  </div>
  <div v-else-if="state === 'error'" class="notice danger" role="alert" data-test="error">
    <strong>{{ error?.message ?? 'Something went wrong.' }}</strong>
    <div v-if="error?.status" class="muted mono">HTTP {{ error.status }}</div>
    <div v-if="error?.traceId" class="muted mono">trace {{ error.traceId }}</div>
    <p style="margin: 8px 0 0"><button @click="$emit('retry')">Retry</button></p>
  </div>
  <div v-else-if="state === 'empty'" class="card muted" data-test="empty">
    <slot name="empty">{{ emptyText }}</slot>
  </div>
  <slot v-else />
</template>
