<script setup lang="ts">
defineProps<{ state: 'ok' | 'fail' | 'running' | 'idle' }>()
</script>

<template>
  <span class="status-dot" :class="state" />
</template>

<style scoped>
.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--border-hover);
}
.ok {
  background: var(--ok-dot);
}
.fail {
  background: #ff6b6b;
}
.running {
  background: var(--accent);
  animation: pulse 1s ease-in-out infinite;
}
@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}
</style>
