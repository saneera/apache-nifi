<script setup lang="ts">
import { ref, watch } from 'vue'
import { deployService } from '../services/deployService'
import { nifiService } from '../services/nifiService'
import { useToast } from 'vue-toastification'

const props = defineProps<{
  flowId: string
  flowName: string
}>()

const versions = ref<any[]>([])
const toast = useToast()

// 🔥 modal state
const showRollback = ref(false)
const selectedVersion = ref<number | null>(null)
const currentVersion = ref<number | null>(null)


const load = async () => {
  versions.value = await nifiService.flowVersions(props.flowId)

  currentVersion.value = await nifiService.getCurrentVersion(
      props.flowName
  )
}

watch(() => props.flowId, load, { immediate: true })

// open modal
const confirmRollback = (version: number) => {
  selectedVersion.value = version
  showRollback.value = true
}

// actual rollback
const doRollback = async () => {
  try {
    await deployService.rollbackFlow(
        props.flowName,
        selectedVersion.value!
    )

    toast.success(`Rolled back to version ${selectedVersion.value}`)

    showRollback.value = false

    await load()

  } catch {
    toast.error('Rollback failed')
  }
}
</script>

<template>
  <div>

    <h2 class="font-bold mb-3">
      Versions - {{ flowName }}
    </h2>

    <table class="w-full text-sm">
      <tr
          v-for="v in versions"
          :key="v.version"
          class="border-b"
      >
        <td class="py-2 flex items-center gap-2">

          <!-- VERSION -->
          <span>Version {{ v.version }}</span>

          <!-- 🔥 ACTIVE BADGE -->
          <span
              v-if="v.version === currentVersion"
              class="bg-green-500 text-white px-2 py-1 rounded text-xs"
          >
      ACTIVE
    </span>

        </td>

        <td>
          {{ v.snapshotMetadata?.timestamp }}
        </td>

        <td>
          <button
              class="bg-blue-500 text-white px-2 py-1 rounded"
              :disabled="v.version === currentVersion"
              @click="confirmRollback(v.version)"
          >
            Rollback
          </button>
        </td>
      </tr>
    </table>

    <!-- 🔥 ROLLBACK MODAL -->
    <div
        v-if="showRollback"
        class="fixed inset-0 bg-black bg-opacity-40 flex items-center justify-center z-50"
    >
      <div class="bg-white p-4 rounded w-80">

        <p class="mb-4">
          Rollback to version {{ selectedVersion }}?
        </p>

        <div class="flex justify-end gap-2">

          <button @click="showRollback=false">
            Cancel
          </button>

          <button
              class="bg-red-500 text-white px-3 py-1 rounded"
              @click="doRollback"
          >
            Confirm
          </button>

        </div>

      </div>
    </div>

  </div>
</template>
