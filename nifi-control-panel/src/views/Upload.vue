<script setup lang="ts">
import { ref, watch } from 'vue'
import DiffViewer from '../components/DiffViewer.vue'
import { normalizeFlow } from '../utils/normalizeFlow'
import { diffSummary } from '../utils/diffSummary'
import { nifiService } from '../services/nifiService'
import { deployService } from '../services/deployService'
import { useToast } from 'vue-toastification'

const toast = useToast()

const localJson = ref('{}')
const registryJson = ref('{}')
const summary = ref<string[]>([])

const showConfirm = ref(false)
const loading = ref(false)

let currentFlowName = ''

/**
 * Upload file
 */
const onFile = async (e: any) => {
  const file = e.target.files[0]
  if (!file) return

  const text = await file.text()

  try {
    const parsed = JSON.parse(text)

    // normalize local
    const normalized = normalizeFlow(parsed)
    localJson.value = JSON.stringify(normalized, null, 2)

    // extract flow name
    currentFlowName =
        parsed.flowContents?.name ||
        parsed.header?.flowName

    if (!currentFlowName) {
      toast.error('Flow name not found')
      return
    }

    await loadRegistryFlow(currentFlowName)

  } catch (err) {
    toast.error('Invalid JSON file')
  }
}

/**
 * Load registry flow
 */
const loadRegistryFlow = async (flowName: string) => {
  try {
    const flows = await nifiService.registryFlows()

    const match = flows.find((f: any) => f.name === flowName)

    if (!match) {
      toast.warning('Flow not found in registry')
      registryJson.value = '{}'
      return
    }

    const snapshot = await nifiService.registryFlow(match.identifier)

    const normalized = normalizeFlow(snapshot)

    registryJson.value = JSON.stringify(normalized, null, 2)

  } catch {
    toast.error('Failed to load registry flow')
  }
}

/**
 * Diff summary
 */
watch([localJson, registryJson], () => {
  try {
    const a = JSON.parse(localJson.value)
    const b = JSON.parse(registryJson.value)

    summary.value = diffSummary(a, b)
  } catch {
    summary.value = []
  }
})

/**
 * Open confirm modal
 */
const confirmDeploy = () => {
  showConfirm.value = true
}

/**
 * Deploy
 */
const deploy = async () => {
  try {
    loading.value = true

    const parsed = JSON.parse(localJson.value)

    const res = await deployService.deployFlow(
        currentFlowName,
        parsed
    )

    toast.success(`Deployed version ${res.version} 🚀`)

  } catch {
    toast.error('Deployment failed')
  } finally {
    loading.value = false
    showConfirm.value = false
  }
}
</script>

<template>
  <div>

    <!-- FILE UPLOAD -->
    <div class="mb-4">
      <input type="file" @change="onFile" />
    </div>

    <!-- HEADER -->
    <div class="flex items-center justify-between mb-4">

      <h2 class="text-lg font-bold">
        Flow Diff
      </h2>

      <div class="flex items-center gap-3">

        <!-- STATUS -->
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

        <!-- DEPLOY BUTTON -->
        <button
            class="bg-green-600 hover:bg-green-700 text-white px-4 py-2 rounded"
            :disabled="summary.length === 0 || loading"
            @click="confirmDeploy"
        >
          <span v-if="loading">Deploying...</span>
          <span v-else>🚀 Deploy</span>
        </button>

      </div>
    </div>

    <!-- SUMMARY -->
    <div class="mb-4">
      <h3 class="font-bold mb-2">Changes</h3>

      <div v-if="summary.length === 0" class="text-green-600 text-sm">
        No changes detected
      </div>

      <ul v-else class="text-sm text-red-600 max-h-40 overflow-auto">
        <li v-for="s in summary" :key="s">• {{ s }}</li>
      </ul>
    </div>

    <!-- DIFF VIEW -->
    <DiffViewer
        :original="registryJson"
        :modified="localJson"
    />

    <!-- 🔥 CONFIRM MODAL -->
    <div
        v-if="showConfirm"
        class="fixed inset-0 bg-black bg-opacity-40 flex items-center justify-center z-50"
    >
      <div class="bg-white p-6 rounded w-96">

        <h2 class="font-bold mb-3">Confirm Deployment</h2>

        <p class="text-sm mb-3">
          You are about to deploy changes.
        </p>

        <ul class="text-xs max-h-40 overflow-auto mb-4">
          <li v-for="s in summary" :key="s">• {{ s }}</li>
        </ul>

        <div class="flex justify-end gap-2">

          <button @click="showConfirm = false">
            Cancel
          </button>

          <button
              class="bg-green-600 text-white px-3 py-1 rounded"
              @click="deploy"
          >
            Confirm
          </button>

        </div>

      </div>
    </div>

  </div>
</template>
