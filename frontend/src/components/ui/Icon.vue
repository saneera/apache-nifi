<script setup lang="ts">
// Small inline stroke-icon set (no icon font / CDN needed).
const props = withDefaults(defineProps<{ name: keyof typeof PATHS; size?: number }>(), { size: 18 })
</script>

<script lang="ts">
export const PATHS = {
  check: 'M20 6L9 17l-5-5',
  chevronLeft: 'M15 18l-6-6 6-6',
  chevronRight: 'M9 18l6-6-6-6',
  warning: 'M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z',
  error: 'M12 8v5M12 16h.01M12 3a9 9 0 100 18 9 9 0 000-18z',
  copy: 'M9 9h11v11H9zM5 15H4V4h11v1',
  refresh: 'M21 12a9 9 0 11-3-6.7L21 8M21 3v5h-5',
  loader: 'M12 3a9 9 0 109 9',
  logo: 'M4 7h6v6H4zM14 4h6v6h-6zM14 14h6v6h-6zM10 10l4-3M10 10l4 7',
  lock: 'M6 11h12v10H6zM8 11V7a4 4 0 018 0v4',
} as const
</script>

<template>
  <svg
    :width="props.size"
    :height="props.size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    :class="{ spin: props.name === 'loader' }"
  >
    <path :d="PATHS[props.name]" />
  </svg>
</template>
