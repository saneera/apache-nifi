<template>
  <v-container fluid>
    <v-card>
      <v-card-title class="d-flex align-center flex-wrap ga-2">
        <v-icon icon="mdi-alert-octagon-outline" class="mr-1" />
        Circuit Conflicts
        <v-spacer />
        <v-text-field
          v-model="search"
          prepend-inner-icon="mdi-magnify"
          label="Search"
          density="compact"
          variant="outlined"
          hide-details
          clearable
          style="max-width: 280px"
        />
        <v-btn
          icon="mdi-refresh"
          variant="text"
          :loading="isLoading"
          title="Refresh"
          @click="loadConflicts"
        />
      </v-card-title>

      <!-- Summary -->
      <v-card-text class="d-flex flex-wrap ga-2 pt-0">
        <v-chip color="error" variant="tonal" prepend-icon="mdi-transit-connection-variant">
          {{ conflicts.length }} circuit{{ conflicts.length === 1 ? '' : 's' }} in conflict
        </v-chip>
        <v-chip variant="tonal" prepend-icon="mdi-alert-outline">
          {{ totalElementConflicts }} element conflict{{ totalElementConflicts === 1 ? '' : 's' }}
        </v-chip>
        <v-chip
          v-for="(count, reason) in conflictsByReason"
          :key="reason"
          :color="reasonColour(String(reason))"
          variant="outlined"
        >
          {{ formatReason(String(reason)) }}: {{ count }}
        </v-chip>
      </v-card-text>

      <v-alert
        v-if="notAvailable"
        type="info"
        variant="tonal"
        class="mx-4 mb-4"
        text="Conflict data is not available yet. The backend returned 'not implemented'; enable pre-canned data to preview this view."
      />

      <CircuitConflictsTable
        :conflicts="conflicts"
        :loading="isLoading"
        :search="search ?? ''"
        @select-circuit="goToCircuit"
      />
    </v-card>
  </v-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { CircuitConflicts } from '../api/generated/circuit-manager'
// TODO: point at the same module that exports postCircuit / performCircuitAction
import { getConflictsList } from '../api/circuit-manager-api'
import CircuitConflictsTable from '../components/CircuitConflictsTable.vue'
import { formatReason, reasonColour } from '../utils/conflictFormat'
// TODO: use the same import path as CircuitCreation.vue
import { useNotificationStore } from '../stores/notification'

const notificationStore = useNotificationStore()
const router = useRouter()

const conflicts = ref<CircuitConflicts[]>([])
const isLoading = ref(false)
const notAvailable = ref(false)
const search = ref<string | null>('')

const totalElementConflicts = computed(() =>
  conflicts.value.reduce(
    (sum, c) => sum + (c.fragments ?? []).reduce((s, f) => s + (f.elements ?? []).length, 0),
    0,
  ),
)

const conflictsByReason = computed(() => {
  const counts: Record<string, number> = {}
  for (const c of conflicts.value) {
    for (const f of c.fragments ?? []) {
      for (const e of f.elements ?? []) {
        const reason = String(e.reason ?? 'Unknown')
        counts[reason] = (counts[reason] ?? 0) + 1
      }
    }
  }
  return counts
})

async function loadConflicts() {
  isLoading.value = true
  notAvailable.value = false
  try {
    conflicts.value = await getConflictsList()
  } catch (error: unknown) {
    conflicts.value = []
    if (statusOf(error) === 501) {
      notAvailable.value = true
    } else {
      // TODO: match the notification call used elsewhere (e.g. notificationStore.error(...))
      notificationStore.addNotification({
        type: 'error',
        message: 'Failed to load circuit conflicts',
      })
    }
  } finally {
    isLoading.value = false
  }
}

/** Works for axios-style ({ response: { status } }) and fetch-style ({ status }) errors. */
function statusOf(error: unknown): number | undefined {
  const e = error as { status?: number; response?: { status?: number } }
  return e?.response?.status ?? e?.status
}

function goToCircuit(circuitId: string) {
  // TODO: use the real circuit detail route name / params
  router.push({ name: 'CircuitExecution', query: { circuitId } })
}

onMounted(loadConflicts)
</script>
