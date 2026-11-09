<template>
  <v-progress-linear v-if="loading" indeterminate color="primary" />

  <div v-else-if="elementCount === 0" id="circuit-details-no-conflicts">No circuit conflicts</div>

  <CircuitConflictDetails v-else :conflict="conflict" :show-title="false" />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { CircuitConflicts } from '../api/generated/circuit-manager'
// TODO: use the same import path as the other components
import { getCircuitConflicts } from '../api/circuit-manager-api'
import { useNotificationStore } from '../stores/notification'
import CircuitConflictDetails from './CircuitConflictDetails.vue'

const props = defineProps<{
  circuitId: string | undefined
}>()

const notificationStore = useNotificationStore()

const conflict = ref<CircuitConflicts | undefined>(undefined)
const loading = ref(false)

const elementCount = computed(() =>
    (conflict.value?.fragments ?? []).reduce((sum, f) => sum + (f.elements ?? []).length, 0),
)

// Reload whenever a different circuit is selected in the table above
watch(() => props.circuitId, loadConflicts, { immediate: true })

async function loadConflicts(circuitId: string | undefined) {
  conflict.value = undefined
  if (!circuitId) return

  loading.value = true
  try {
    const result = await getCircuitConflicts(circuitId)
    // Ignore a late response if the user has already selected another circuit
    if (circuitId === props.circuitId) conflict.value = result
  } catch (error: any) {
    if (circuitId === props.circuitId) {
      notificationStore.error(`Failed to load circuit conflicts: ${error?.message ?? error}`)
    }
  } finally {
    if (circuitId === props.circuitId) loading.value = false
  }
}
</script>


ech-debt ticket:

Title: Remove unused toolbar config (setToolbarConfig) from views

Type: Tech debt (child of JTTHFC2-2908)

Description:
Several views call commonComponentsStore.setToolbarConfig(...) in onActivated to set an app bar title and buttons (e.g. CircuitTemplates.vue, CircuitConflicts.vue). The toolbar config doesn’t appear to be rendered anywhere and may be left over from an app bar that was removed. Raised from PR 8660 review.

Tasks:

Confirm nothing reads toolbarConfig (app bar component, layouts, common-components)
Remove setToolbarConfig calls and the circuitManagerAppBar objects from all views
Remove setToolbarConfig / toolbarConfig from @babcock/common-components if it has no other consumers

Acceptance criteria:

No views call setToolbarConfig
No visible change to the UI
Build, lint and tests pass

Once you’ve created it, swap the real ticket number in for JTTHFC2-XXXX in your reply.
