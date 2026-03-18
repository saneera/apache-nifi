<script setup lang="ts">
defineProps<{
  message: string
  onRetry?: () => void
}>()
</script>

<template>
  <div class="bg-red-100 border border-red-300 p-4 rounded">
    <div class="text-red-700 font-semibold mb-2">
      Error
    </div>

    <div class="text-sm mb-3">
      {{ message }}
    </div>

    <button
        v-if="onRetry"
        @click="onRetry"
        class="bg-red-500 text-white px-3 py-1 rounded"
    >
      Retry
    </button>
  </div>
</template>
