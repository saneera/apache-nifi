// Shared display helpers for circuit conflicts

/** FREQUENCY_ALLOCATION -> Frequency allocation */
export function formatReason(reason: string): string {
  if (!reason) return 'Unknown'
  const words = reason.replace(/[_-]+/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

// TODO: map to the real ConflictReason values once confirmed
export function reasonColour(reason: string): string {
  const r = reason.toLowerCase()
  if (r.includes('freq')) return 'warning'
  if (r.includes('asset') || r.includes('hardware') || r.includes('resource')) return 'error'
  return 'info'
}
