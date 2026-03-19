<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { nifiService } from '../services/nifiService'
import Versions from '../components/Versions.vue'

const flows = ref<any[]>([])
const versions = ref<Record<string, any[]>>({})
const selectedFlow = ref<any | null>(null)

onMounted(async () => {
  const r = await nifiService.registryFlows()
  flows.value = r.data
})

const loadVersions = async (f: any) => {
  const r = await nifiService.registryVersions(f.identifier)
  versions.value[f.identifier] = r.data
}

const openVersions = (flow: any) => {
  selectedFlow.value = flow
}

</script>

<template>
  <div class="grid grid-cols-3 gap-4">

    <!-- LEFT: Flow list -->
    <div class="bg-white p-4 rounded shadow">

      <h2 class="font-bold mb-3">Registry Flows</h2>

      <div
          v-for="f in flows"
          :key="f.identifier"
          class="flex justify-between p-2 hover:bg-gray-100 rounded"
      >
        <span>{{ f.name }}</span>

        <button
            class="text-blue-500 text-sm"
            @click="openVersions(f)"
        >
          Versions
        </button>
      </div>
    </div>

    <!-- RIGHT: Versions -->
    <div class="col-span-2">
      <Versions
          v-if="selectedFlow"
          :flowId="selectedFlow.identifier"
          :flowName="selectedFlow.name"
      />
    </div>

  </div>
</template>
