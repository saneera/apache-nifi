<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { nifiService } from '../services/nifiService'

const flows = ref<any[]>([])
const versions = ref<Record<string, any[]>>({})

onMounted(async () => {
  const r = await nifiService.registryFlows()
  flows.value = r.data
})

const loadVersions = async (f: any) => {
  const r = await nifiService.registryVersions(f.identifier)
  versions.value[f.identifier] = r.data
}
</script>

<template>
  <div>
    <h2 class="text-xl mb-4 font-semibold">Registry</h2>
    <div v-for="f in flows" :key="f.identifier" class="card">
      <div>
        <div class="font-semibold">{{ f.name }}</div>
        <div class="text-xs text-gray-500">{{ f.identifier }}</div>
      </div>
      <button class="btn" @click="loadVersions(f)">Versions</button>
      <div v-if="versions[f.identifier]" class="w-full mt-3 text-sm">
        <div v-for="v in versions[f.identifier]" :key="v.version">
          Version: {{ v.version }}
        </div>
      </div>
    </div>
  </div>
</template>
