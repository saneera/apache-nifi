<template>
  <v-data-table
    :headers="headers"
    :items="rows"
    :sort-by="[{ key: 'circuitId', order: 'asc' }]"
    :loading="loading"
    :search="search"
    item-value="key"
    density="compact"
    class="circuit-conflicts-table"
  >
    <template #item.circuitId="{ item }">
      <span class="font-weight-medium" :title="item.circuitId">{{ circuitLabel(item.circuitId) }}</span>
    </template>

    <template #item.reason="{ item }">
      <v-chip :color="reasonColour(item.reason)" size="small" variant="tonal">
        {{ formatReason(item.reason) }}
      </v-chip>
    </template>

    <template #item.conflictingCircuits="{ item }">
      <v-chip
        v-for="id in item.conflictingCircuits"
        :key="id"
        size="small"
        class="mr-1 my-1"
        variant="outlined"
        :title="id"
        @click="emit('select-circuit', id)"
      >
        {{ circuitLabel(id) }}
      </v-chip>
    </template>

    <template #item.resolutionStatus="{ item }">
      <span v-if="item.resolutionStatus">{{ item.resolutionStatus }}</span>
      <span v-else class="text-medium-emphasis">-</span>
    </template>

    <template #no-data>
      <div class="pa-4 text-medium-emphasis">
        <v-icon icon="mdi-check-circle-outline" color="success" class="mr-1" />
        No conflicts
      </div>
    </template>
  </v-data-table>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CircuitConflicts } from '../api/generated/circuit-manager'
import { formatReason, reasonColour } from '../utils/conflictFormat'

const props = withDefaults(
  defineProps<{
    conflicts: CircuitConflicts[]
    loading?: boolean
    search?: string
    /** Show the Circuit column. Turn off when showing a single circuit (e.g. circuit detail view). */
    showCircuitColumn?: boolean
    /** Optional id -> name lookup so circuits show by name instead of UUID. */
    circuitNames?: Record<string, string>
  }>(),
  { loading: false, search: '', showCircuitColumn: true, circuitNames: () => ({}) },
)

const emit = defineEmits<{
  (e: 'select-circuit', circuitId: string): void
}>()

/** One table row per conflicting element. */
interface ConflictRow {
  key: string
  circuitId: string
  fragmentId: string
  elementId: string
  reason: string
  conflictingCircuits: string[]
  resolutionStatus?: string
}

const rows = computed<ConflictRow[]>(() =>
  props.conflicts.flatMap((circuit) =>
    (circuit.fragments ?? []).flatMap((fragment) =>
      (fragment.elements ?? []).map((element) => ({
        key: `${circuit.circuitId}/${fragment.id}/${element.id}`,
        circuitId: circuit.circuitId,
        fragmentId: fragment.id,
        elementId: element.id,
        reason: String(element.reason ?? ''),
        conflictingCircuits: element.conflictingCircuits ?? [],
        resolutionStatus: (circuit.resolution as Record<string, unknown> | undefined)?.status as
          | string
          | undefined,
      })),
    ),
  ),
)

const headers = computed(() => [
  ...(props.showCircuitColumn ? [{ title: 'Circuit', key: 'circuitId' }] : []),
  { title: 'Fragment', key: 'fragmentId' },
  { title: 'Element', key: 'elementId' },
  { title: 'Reason', key: 'reason' },
  { title: 'Conflicting circuits', key: 'conflictingCircuits', sortable: false },
  { title: 'Resolution', key: 'resolutionStatus' },
])

function circuitLabel(id: string): string {
  return props.circuitNames[id] ?? shortId(id)
}

function shortId(id: string): string {
  return id?.length > 8 ? id.slice(0, 8) : id
}
</script>
