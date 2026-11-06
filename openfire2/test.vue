<template>
  <v-expansion-panel id="circuit-details-conflicts-panel">
    <v-expansion-panel-title>
      Conflicts
      <v-chip
          v-if="elementCount > 0"
          id="circuit-details-conflicts-count"
          color="error"
          size="small"
          class="ml-2"
      >
        {{ elementCount }}
      </v-chip>
    </v-expansion-panel-title>

    <v-expansion-panel-text>
      <v-progress-linear v-if="loading" indeterminate color="primary" />

      <div v-else-if="elementCount === 0" id="circuit-details-no-conflicts">No circuit conflicts</div>

      <CircuitConflictDetails v-else :conflict="conflict" :show-title="false" />
    </v-expansion-panel-text>
  </v-expansion-panel>
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


====



<template>
  <v-card
      v-if="props.conflict"
      :title="props.showTitle ? `Conflicts for circuit ${shortId(props.conflict.circuitId)}` : undefined"
      :subtitle="props.showTitle ? props.conflict.circuitId : undefined"
      flat
      width="100%"
  >
    <template #text>
      <v-data-table
          id="circuit-conflict-details-elements"
          class="border-sm elevation-2"
          :headers="headers"
          :items="rows"
          :sort-by="[{ key: 'fragmentId', order: 'asc' }]"
          items-per-page="-1"
          density="compact"
          hide-default-footer
      >
        <template #item.reason="{ item }">
          <v-chip :color="reasonColour(String(item.reason))" size="small">
            {{ formatReason(String(item.reason)) }}
          </v-chip>
        </template>

        <template #item.conflictingCircuits="{ item }">
          <v-chip
              v-for="id in item.conflictingCircuits"
              :key="id"
              :title="id"
              color="blue-darken-2"
              size="small"
              class="mr-1 my-1"
          >
            {{ shortId(id) }}
          </v-chip>
        </template>
      </v-data-table>

      <div v-if="resolutionEntries.length" id="circuit-conflict-details-resolution" class="mt-4">
        <div class="text-subtitle-1 mb-2">Resolution</div>
        <v-table density="compact" class="border-sm">
          <tbody>
          <tr v-for="[key, value] in resolutionEntries" :key="key">
            <td class="font-weight-medium" style="width: 220px">{{ formatKey(key) }}</td>
            <td>{{ value }}</td>
          </tr>
          </tbody>
        </v-table>
      </div>
    </template>
  </v-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import { CircuitConflicts, ElementConflict, FragmentConflict } from '../api/generated/circuit-manager'
import { formatReason, reasonColour, shortId } from '../utils/conflictFormat'

const props = withDefaults(
    defineProps<{
      conflict: CircuitConflicts | undefined
      /** Hide the card title when embedded somewhere that already names the circuit (e.g. Circuit Details panel). */
      showTitle?: boolean
    }>(),
    { showTitle: true },
)

const headers = [
  { title: 'Fragment', key: 'fragmentId' },
  { title: 'Element', key: 'id' },
  { title: 'Reason', key: 'reason' },
  { title: 'Conflicting circuits', key: 'conflictingCircuits', sortable: false },
]

/** Generated ElementConflict plus the id of the fragment it belongs to, so fragments can be flattened into one table. */
type ElementRow = ElementConflict & { fragmentId: FragmentConflict['id'] }

/** One row per conflicting element. */
const rows = computed<ElementRow[]>(() =>
    (props.conflict?.fragments ?? []).flatMap((fragment) =>
        (fragment.elements ?? []).map((element) => ({ ...element, fragmentId: fragment.id })),
    ),
)

const resolutionEntries = computed(() =>
    Object.entries(props.conflict?.resolution ?? {}).map(
        ([key, value]) => [key, typeof value === 'object' ? JSON.stringify(value) : String(value)] as const,
    ),
)

/** suggestedAction -> Suggested action */
function formatKey(key: string) {
  const words = key.replace(/([A-Z])/g, ' $1').replace(/[_-]+/g, ' ').trim().toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}
</script>
</script>
