<template>
  <v-card
    v-if="props.conflict"
    :title="`Conflicts for circuit ${shortId(props.conflict.circuitId)}`"
    :subtitle="props.conflict.circuitId"
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
          <v-chip :color="reasonColour(item.reason)" size="small">
            {{ formatReason(item.reason) }}
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

import { CircuitConflicts } from '../api/generated/circuit-manager'
import { formatReason, reasonColour, shortId } from '../utils/conflictFormat'

const props = defineProps<{
  conflict: CircuitConflicts | undefined
}>()

const headers = [
  { title: 'Fragment', key: 'fragmentId' },
  { title: 'Element', key: 'elementId' },
  { title: 'Reason', key: 'reason' },
  { title: 'Conflicting circuits', key: 'conflictingCircuits', sortable: false },
]

interface ElementRow {
  fragmentId: string
  elementId: string
  reason: string
  conflictingCircuits: string[]
}

/** One row per conflicting element. */
const rows = computed<ElementRow[]>(() =>
  (props.conflict?.fragments ?? []).flatMap((fragment) =>
    (fragment.elements ?? []).map((element) => ({
      fragmentId: fragment.id,
      elementId: element.id,
      reason: String(element.reason),
      conflictingCircuits: element.conflictingCircuits ?? [],
    })),
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
