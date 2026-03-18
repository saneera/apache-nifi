<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { nifiService } from '../services/nifiService'
import StatusBadge from '../components/StatusBadge.vue'

const flows = ref<any[]>([])

onMounted(async () => {
  flows.value = await nifiService.flows()
})

const toggle = async (f: any) => {
  const state = f.runningCount > 0 ? 'STOPPED' : 'RUNNING'
  await nifiService.toggle(f.component.id, state)
  flows.value = await nifiService.flows()
}
</script>

<template>
  <div>
    <h2 class="text-xl mb-4 font-semibold">Dashboard</h2>
    <div v-for="f in flows" :key="f.component.id" class="card">
      <div>
        <div class="font-semibold">{{ f.component.name }}</div>
        <div class="text-sm text-gray-500">Version: {{ f.versionedFlowState }}</div>
      </div>
      <div class="flex items-center gap-3">
        <StatusBadge :state="f.runningCount>0 ? 'RUNNING' : 'STOPPED'" />
        <button @click="toggle(f)" class="btn">
          {{ f.runningCount>0 ? 'Stop' : 'Start' }}
        </button>
      </div>
    </div>
  </div>
</template>
