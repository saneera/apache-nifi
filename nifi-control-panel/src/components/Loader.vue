<script setup lang="ts">
import { useLoadingStore } from '../store/loading'

const loader = useLoadingStore()
</script>

<template>
  <div
      v-if="loader.loading"
      class="fixed inset-0 bg-black bg-opacity-30 flex items-center justify-center z-50"
  >
    <div class="bg-white p-4 rounded shadow flex items-center gap-3">

      <div class="animate-spin rounded-full h-6 w-6 border-b-2 border-blue-600"></div>

      <span class="text-sm">Loading...</span>
    </div>
  </div>
</template>
