export function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

export function formatDuration(ms) {
  if (ms === null || ms === undefined) return '—'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

export function formatUptime(seconds) {
  if (seconds === null || seconds === undefined) return '—'
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (d > 0) return `${d}d ${h}h ${m}m`
  if (h > 0) return `${h}h ${m}m`
  return m > 0 ? `${m}m ${s}s` : `${s}s`
}

/** Flattens a JSON Schema's top-level `properties` into table rows. */
export function schemaParameters(schema) {
  if (!schema || typeof schema !== 'object' || typeof schema.properties !== 'object') return []
  const required = new Set(Array.isArray(schema.required) ? schema.required : [])
  return Object.entries(schema.properties).map(([name, definition]) => ({
    name,
    type: Array.isArray(definition?.type) ? definition.type.join(' | ') : (definition?.type ?? '—'),
    required: required.has(name),
    description: definition?.description ?? '',
    allowed: Array.isArray(definition?.enum) ? definition.enum.join(', ') : ''
  }))
}
