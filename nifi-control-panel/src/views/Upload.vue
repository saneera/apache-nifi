<script setup lang="ts">
import { ref } from 'vue'
import DiffViewer from '../components/DiffViewer.vue'

import { normalizeFlow } from '../utils/normalizeFlow'
import {nifiService} from "../services/nifiService";

import { deployService } from '../services/deployService'
import { useToast } from 'vue-toastification'
import {diffSummary} from "../utils/diffSummary";

const file = ref<File|null>(null)
const localJson = ref('{}')
const registryJson = ref('{}')

const summary = ref<string[]>([])


const toast = useToast()
const loading = ref(false)

const deploy = async () => {
  try {
    loading.value = true

    const parsed = JSON.parse(localJson.value)

    const flowName =
        parsed.flowContents?.name ||
        parsed.header?.flowName

    const res = await deployService.deployFlow(flowName, parsed)

    toast.success(`Deployed version ${res.version} 🚀`)
  } catch (e) {
    toast.error('Deployment failed')
  } finally {
    loading.value = false
    showConfirm.value = false
  }
}


const onFile = async (e: any) => {
  const file = e.target.files[0]
  if (!file) return

  const text = await file.text()

  try {
    const parsed = JSON.parse(text)

    const normalized = normalizeFlow(parsed)

    localJson.value = JSON.stringify(normalized, null, 2)

    // 🔥 extract flow name
    const flowName =
        parsed.flowContents?.name ||
        parsed.header?.flowName

// 🔥 load registry version
    await loadRegistryFlow(flowName)
  } catch {
    localJson.value = text
  }
}

const loadRegistryFlow = async (flowName: string) => {
  const flows = await nifiService.registryFlows()

  const match = flows.find((f: any) => f.name === flowName)

  if (!match) {
    console.warn('Flow not found in registry')
    return
  }

  const snapshot = await nifiService.registryFlow(match.identifier)

  const normalized = normalizeFlow(snapshot)

  registryJson.value = JSON.stringify(normalized, null, 2)
}

function simpleHash(s: string) {
  let h = 0, i = 0, len = s.length
  while (i < len) { h = (h << 5) - h + s.charCodeAt(i++) | 0 }
  return String(h)
}


summary.value = diffSummary(
    JSON.parse(localJson.value),
    JSON.parse(registryJson.value)
)

</script>

<template>
  <div>

    <!-- HEADER -->
    <div class="flex items-center justify-between mb-4">

      <!-- LEFT: Title -->
      <h2 class="text-lg font-bold">
        Flow Diff
      </h2>

      <!-- RIGHT: Actions -->
      <div class="flex items-center gap-3">

        <!-- STATUS BADGE -->
        <span
            v-if="summary.length === 0"
            class="bg-green-500 text-white px-2 py-1 rounded text-xs"
        >
          Synced
        </span>

        <span
            v-else
            class="bg-yellow-500 text-white px-2 py-1 rounded text-xs"
        >
          Changes Detected
        </span>

        <!-- 🚀 DEPLOY BUTTON -->
        <button
            class="bg-green-600 hover:bg-green-700 text-white px-4 py-2 rounded"
            :disabled="summary.length === 0"
            @click="confirmDeploy"
        >
          🚀 Deploy
        </button>

      </div>
    </div>

    <!-- DIFF SUMMARY -->
    <div class="mb-4">
      <ul v-if="summary.length" class="text-sm text-red-600">
        <li v-for="s in summary" :key="s">• {{ s }}</li>
      </ul>
      <div v-else class="text-green-600 text-sm">
        No changes detected
      </div>
    </div>

    <!-- DIFF VIEWER -->
    <DiffViewer
        :original="registryJson"
        :modified="localJson"
    />

  </div>
</template>
