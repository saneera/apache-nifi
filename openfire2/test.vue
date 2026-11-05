<template>
  <v-card flat width="100%">
    <template #subtitle>
      <v-text-field
          id="circuit-conflicts-table-search"
          v-model="search"
          label="Search"
          prepend-inner-icon="mdi-magnify"
          variant="outlined"
          hide-details
          single-line
          density="compact"
      />
    </template>

    <template #text>
      <v-data-table
          id="circuit-conflicts-table"
          class="border-sm elevation-2"
          :headers="headers"
          :items="conflicts"
          :search="search"
          :loading="loading"
          item-value="circuitId"
          items-per-page="5"
          :row-props="rowProps"
          @click:row="selectRow"
      >
        <template #item.circuitId="{ item }">
          <span :title="item.circuitId">{{ shortId(item.circuitId) }}</span>
        </template>

        <template #item.reasons="{ item }">
          <v-chip
              v-for="reason in reasonsOf(item)"
              :key="reason"
              :color="reasonColour(reason)"
              size="small"
              class="mr-1"
          >
            {{ formatReason(reason) }}
          </v-chip>
        </template>

        <template #no-data>No circuit conflicts</template>
      </v-data-table>
    </template>
  </v-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { CircuitConflicts, ElementConflict } from '../api/generated/circuit-manager'
// TODO: use the same import path as the other components (e.g. CircuitTemplatesTable.vue)
import { getConflictsList } from '../api/circuit-manager-api'
import { useNotificationStore } from '../stores/notification'
import { formatReason, reasonColour, shortId } from '../utils/conflictFormat'

const emit = defineEmits<{
  (e: 'selected', conflict: CircuitConflicts | undefined): void
}>()

const notificationStore = useNotificationStore()

// Rows are the generated CircuitConflicts type; summary columns are derived with `value` functions
// so sorting and search still work on them.
const headers = [
  { title: 'Circuit', key: 'circuitId' },
  { title: 'Fragments', key: 'fragmentCount', value: (c: CircuitConflicts) => c.fragments?.length ?? 0 },
  { title: 'Element conflicts', key: 'elementCount', value: (c: CircuitConflicts) => elementsOf(c).length },
  {
    title: 'Reasons',
    key: 'reasons',
    value: (c: CircuitConflicts) => reasonsOf(c).map(formatReason).join(' '),
    sortable: false,
  },
  {
    title: 'Conflicting circuits',
    key: 'conflictingCircuitCount',
    value: (c: CircuitConflicts) => new Set(elementsOf(c).flatMap((e) => e.conflictingCircuits ?? [])).size,
  },
]

const conflicts = ref<CircuitConflicts[]>([])
const loading = ref(false)
const search = ref('')
const selectedCircuitId = ref<string | undefined>(undefined)

onMounted(loadConflicts)

async function loadConflicts() {
  loading.value = true
  try {
    conflicts.value = await getConflictsList()
  } catch (error: any) {
    conflicts.value = []
    notificationStore.error(`Failed to load circuit conflicts: ${error?.message ?? error}`)
  } finally {
    loading.value = false
  }
}

function elementsOf(conflict: CircuitConflicts): ElementConflict[] {
  return (conflict.fragments ?? []).flatMap((f) => f.elements ?? [])
}

function reasonsOf(conflict: CircuitConflicts): string[] {
  return [...new Set(elementsOf(conflict).map((e) => String(e.reason)))]
}

function rowProps(value: { item: CircuitConflicts }) {
  if (value.item.circuitId === selectedCircuitId.value) {
    return { style: 'background-color: lightgray' }
  }
}

function selectRow(_: PointerEvent, value: { item: CircuitConflicts }) {
  if (value.item.circuitId === selectedCircuitId.value) {
    selectedCircuitId.value = undefined
    emit('selected', undefined)
    return
  }

  selectedCircuitId.value = value.item.circuitId
  emit('selected', getConflict(selectedCircuitId.value))
}

function getConflict(circuitId: string) {
  return conflicts.value.find((c) => c.circuitId === circuitId)
}
</script>
