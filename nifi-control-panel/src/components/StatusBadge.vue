<script setup lang="ts">
defineProps<{ state: 'RUNNING' | 'STOPPED' | 'UNKNOWN' }>()
</script>
<template>
  <span :class="['badge',
    state==='RUNNING' ? 'bg-green-100 text-green-700' :
    state==='STOPPED' ? 'bg-red-100 text-red-700' :
    'bg-gray-100 text-gray-700'
  ]">
    {{ state }}
  </span>
</template>
