<script setup>
defineProps({
  tone: { type: String, default: 'neutral' }
})
</script>

<template>
  <span class="badge" :class="tone"><slot /></span>
</template>
