<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { nifiService } from '../services/nifiService'

const ctx = ref<any[]>([])

onMounted(async () => {
  const r = await nifiService.parameterContexts()
  ctx.value = r.data.parameterContexts
})
</script>

<template>
  <div>
    <h2 class="text-xl mb-4 font-semibold">Parameter Contexts</h2>
    <div v-for="c in ctx" :key="c.component.id" class="card">
      {{ c.component.name }}
    </div>
  </div>
</template>
