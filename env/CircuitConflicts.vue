<template>
  <v-container fluid class="flex-fill-height">
    <v-col class="scrollable">
      <v-row>
        <CircuitConflictsTable @selected="(selected) => (selectedConflict = selected)" />
      </v-row>
      <v-row>
        <CircuitConflictDetails v-if="selectedConflict" :conflict="selectedConflict" />
      </v-row>
    </v-col>
  </v-container>
</template>

<script setup lang="ts">
import { useCommonComponentsStore } from '@babcock/common-components'
import { ref } from 'vue'
import { onActivated } from 'vue'

import { CircuitConflicts } from '../api/generated/circuit-manager'
import CircuitConflictDetails from '../components/CircuitConflictDetails.vue'
import CircuitConflictsTable from '../components/CircuitConflictsTable.vue'

const commonComponentsStore = useCommonComponentsStore()

const circuitManagerAppBar: any = {
  title: 'Circuit Conflicts',
  buttons: [],
}

onActivated(async () => {
  commonComponentsStore.setToolbarConfig(circuitManagerAppBar)
})

const selectedConflict = ref<CircuitConflicts | undefined>(undefined)
</script>
