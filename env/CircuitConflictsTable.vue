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
      <v-alert
        v-if="notAvailable"
        id="circuit-conflicts-table-not-available"
        type="info"
        variant="tonal"
        class="mb-4"
        text="Circuit conflict data is not available yet."
      />

      <v-data-table
        id="circuit-conflicts-table"
        class="border-sm elevation-2"
        :headers="headers"
        :items="rows"
        :search="search"
        :loading="loading"
        items-per-page="5"
        :row-props="rowProps"
        @click:row="selectRow"
      >
        <template #item.circuitId="{ item }">
          <span :title="item.circuitId">{{ shortId(item.circuitId) }}</span>
        </template>

        <template #item.reasons="{ item }">
          <v-chip
            v-for="reason in item.reasons"
            :key="reason"
            :color="reasonColour(reason)"
            size="small"
            class="mr-1"
          >
            {{ formatReason(reason) }}
          </v-chip>
        </template>

        <template #item.resolutionStatus="{ item }">
          <v-chip v-if="item.resolutionStatus" color="blue-darken-2" size="small">
            {{ item.resolutionStatus }}
          </v-chip>
        </template>

        <template #no-data>No circuit conflicts</template>
      </v-data-table>
    </template>
  </v-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { CircuitConflicts } from '../api/generated/circuit-manager'
// TODO: use the same import path as the other components (e.g. CircuitTemplatesTable.vue)
import { getConflictsList } from '../api/circuit-manager-api'
import { useNotificationStore } from '../stores/notification'
import { formatReason, reasonColour, shortId } from '../utils/conflictFormat'

const emit = defineEmits<{
  (e: 'selected', conflict: CircuitConflicts | undefined): void
}>()

const notificationStore = useNotificationStore()

const headers = [
  { title: 'Circuit', key: 'circuitId' },
  { title: 'Fragments', key: 'fragmentCount' },
  { title: 'Element conflicts', key: 'elementCount' },
  { title: 'Reasons', key: 'reasons', sortable: false },
  { title: 'Conflicting circuits', key: 'conflictingCircuitCount' },
  { title: 'Resolution', key: 'resolutionStatus' },
]

interface ConflictRow {
  circuitId: string
  fragmentCount: number
  elementCount: number
  reasons: string[]
  conflictingCircuitCount: number
  resolutionStatus?: string
}

const conflicts = ref<CircuitConflicts[]>([])
const loading = ref(false)
const notAvailable = ref(false)
const search = ref('')
const selectedCircuitId = ref<string | undefined>(undefined)

/** One summary row per circuit in conflict. */
const rows = computed<ConflictRow[]>(() =>
  conflicts.value.map((conflict) => {
    const elements = (conflict.fragments ?? []).flatMap((f) => f.elements ?? [])
    return {
      circuitId: conflict.circuitId,
      fragmentCount: (conflict.fragments ?? []).length,
      elementCount: elements.length,
      reasons: [...new Set(elements.map((e) => String(e.reason)))],
      conflictingCircuitCount: new Set(elements.flatMap((e) => e.conflictingCircuits ?? [])).size,
      resolutionStatus: conflict.resolution?.status as string | undefined,
    }
  }),
)

onMounted(loadConflicts)

async function loadConflicts() {
  loading.value = true
  notAvailable.value = false
  try {
    conflicts.value = await getConflictsList()
  } catch (error: any) {
    conflicts.value = []
    if (error?.status === 501) {
      notAvailable.value = true
    } else {
      notificationStore.error(`Failed to load circuit conflicts: ${error?.message ?? error}`)
    }
  } finally {
    loading.value = false
  }
}

function rowProps(value: { item: ConflictRow }) {
  if (value.item.circuitId === selectedCircuitId.value) {
    return { style: 'background-color: lightgray' }
  }
}

function selectRow(_: PointerEvent, value: { item: ConflictRow }) {
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
