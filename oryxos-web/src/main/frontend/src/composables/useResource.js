import { onMounted, ref } from 'vue'

/**
 * Loads one resource and tracks `state`: 'loading' | 'error' | 'empty' | 'ready'.
 * `isEmpty` decides when a successful answer counts as "nothing to show".
 */
export function useResource(loader, { isEmpty = defaultIsEmpty, immediate = true } = {}) {
  const state = ref('loading')
  const data = ref(null)
  const error = ref(null)
  let generation = 0

  async function reload() {
    const mine = ++generation
    state.value = 'loading'
    error.value = null
    try {
      const result = await loader()
      if (mine !== generation) return
      data.value = result
      state.value = isEmpty(result) ? 'empty' : 'ready'
    } catch (failure) {
      if (mine !== generation) return
      error.value = failure
      data.value = null
      state.value = 'error'
    }
  }

  if (immediate) onMounted(reload)
  return { state, data, error, reload }
}

function defaultIsEmpty(value) {
  return Array.isArray(value) ? value.length === 0 : value === null || value === undefined
}
