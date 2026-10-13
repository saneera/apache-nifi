<!--
  Add/Delete fragment section, built like CircuitOperations.vue:
  a table with two operations (Add fragment, Delete fragment), each with
  CONFIGURE → form → Cancel / Run, and a Result chip.
-->
<template>
  <v-data-table-virtual
      id="fragment-operations-table"
      v-model:expanded="expandedRows"
      :headers="headers"
      :items="items"
      item-value="id"
      density="compact"
      class="mt-2"
  >
    <template #item.data-table-expand="{ item }">
      <v-btn
          :id="`fragment-operations-configure-${item.id}`"
          size="small"
          variant="tonal"
          prepend-icon="mdi-cog"
          @click="toggleExpandedRow(item.id)"
      >
        Configure
      </v-btn>
    </template>

    <template #item.result="{ item }">
      <v-progress-circular v-if="item.loading" indeterminate size="20" />
      <v-chip
          v-else
          :color="getResultColor(item.result)"
          :prepend-icon="getResultIcon(item.result)"
          size="small"
      >
        {{ item.result }}
      </v-chip>
    </template>

    <template #expanded-row="{ columns, item }">
      <tr>
        <td :colspan="columns.length">
          <v-form :ref="(el) => (forms[item.id] = el)" class="py-3" @submit.prevent="run(item)">
            <v-select
                :id="`fragment-operations-fragment-${item.id}`"
                v-model="selectedFragment[item.id]"
                :items="fragmentOptions"
                item-title="title"
                item-value="value"
                label="Fragment"
                hint="Fragments with a template minimum greater than 0"
                persistent-hint
                :rules="[(v: string) => !!v || 'This field is required']"
                variant="solo"
                density="compact"
            />

            <v-row class="mt-2">
              <v-spacer />
              <v-btn
                  :id="`fragment-operations-cancel-${item.id}`"
                  size="small"
                  variant="flat"
                  color="grey"
                  prepend-icon="mdi-close"
                  class="mr-2"
                  @click="toggleExpandedRow(item.id)"
              >
                Cancel
              </v-btn>
              <v-btn
                  :id="`fragment-operations-run-${item.id}`"
                  size="small"
                  color="primary"
                  prepend-icon="mdi-play"
                  type="submit"
                  :loading="item.loading"
              >
                Run
              </v-btn>
            </v-row>
          </v-form>
        </td>
      </tr>
    </template>
  </v-data-table-virtual>
</template>

<script setup lang="ts">
import { useNotificationStore } from '@babcock/common-components'
import { computed, ref } from 'vue'

import type { CircuitTemplate } from '../../api/generated/circuit-manager'
// TODO: point these at the real add / delete fragment requests
import { addFragment, deleteFragment } from '../../api/requests'
import { OperationResult } from '../../types/OperationResult'
import { getConfigurableFragments } from '../../utils/templateFragments'

const props = defineProps<{
  circuitId: string
  template: CircuitTemplate
}>()

const emit = defineEmits<{ updated: [] }>()

type FragmentOperationId = 'add-fragment' | 'delete-fragment'

interface FragmentOperationItem {
  id: FragmentOperationId
  name: string
  description: string
  result: OperationResult
  loading: boolean
}

const notificationStore = useNotificationStore()

const headers = [
  { title: 'Operation name', key: 'name' },
  { title: 'Description', key: 'description' },
  { title: 'Actions', key: 'data-table-expand', width: '250px' },
  { title: 'Result', key: 'result' },
]

const items = ref<FragmentOperationItem[]>([
  {
    id: 'add-fragment',
    name: 'Add fragment',
    description: 'Add a fragment to the circuit',
    result: OperationResult.NOT_RUN,
    loading: false,
  },
  {
    id: 'delete-fragment',
    name: 'Delete fragment',
    description: 'Delete a fragment from the circuit',
    result: OperationResult.NOT_RUN,
    loading: false,
  },
])

const expandedRows = ref<string[]>([])
const forms = ref<Record<string, any>>({})
const selectedFragment = ref<Record<string, string | undefined>>({})

const fragmentOptions = computed(() =>
    getConfigurableFragments(props.template).map((f) => ({
      title: `${f.name} (min ${f.min}${f.max != null ? `, max ${f.max}` : ''})`,
      value: f.name,
    })),
)

function toggleExpandedRow(id: string) {
  expandedRows.value = expandedRows.value.includes(id)
      ? expandedRows.value.filter((r) => r !== id)
      : [...expandedRows.value, id]
}

async function run(item: FragmentOperationItem) {
  const { valid } = (await forms.value[item.id]?.validate()) ?? { valid: false }
  const fragment = selectedFragment.value[item.id]
  if (!valid || !fragment) return

  item.loading = true
  try {
    const response =
        item.id === 'add-fragment'
            ? await addFragment(props.circuitId, fragment)
            : await deleteFragment(props.circuitId, fragment)

    item.result =
        response >= 200 && response < 300 ? OperationResult.SUCCESS : OperationResult.FAILURE

    if (item.result === OperationResult.SUCCESS) {
      toggleExpandedRow(item.id)
      emit('updated') // let CircuitDetails refresh the Fragments panel
    }
  } catch (error: any) {
    item.result = OperationResult.FAILURE
    notificationStore.error(`${item.name} failed: ${error.message}`)
    console.error(error)
  } finally {
    item.loading = false
  }
}

// Same look as CircuitOperations.vue. Swap for its helpers if they're exported.
function getResultColor(result: OperationResult): string {
  switch (result) {
    case OperationResult.SUCCESS:
      return 'success'
    case OperationResult.FAILURE:
      return 'error'
    default:
      return ''
  }
}

function getResultIcon(result: OperationResult): string {
  switch (result) {
    case OperationResult.SUCCESS:
      return 'mdi-check-circle'
    case OperationResult.FAILURE:
      return 'mdi-close-circle'
    default:
      return ''
  }
}
</script>


=========

import type { CircuitTemplate } from '../api/generated/circuit-manager'

export interface TemplateFragment {
name: string
min: number
max?: number
}

/**
* Fragments defined in the circuit template, with their min / max.
* TODO: adjust `template.fragments` and the `min` / `max` field names to match the
* CircuitTemplate type in circuit-manager.ts if they differ.
*/
export function getTemplateFragments(template?: CircuitTemplate): TemplateFragment[] {
const fragments = ((template as any)?.fragments ?? {}) as Record<string, any>
return Object.entries(fragments).map(([name, f]) => ({
name: f?.name ?? name,
min: Number(f?.min ?? 0),
max: f?.max != null ? Number(f.max) : undefined,
}))
}

/** Fragments that can be added / deleted: template `min` greater than 0. */
export function getConfigurableFragments(template?: CircuitTemplate): TemplateFragment[] {
return getTemplateFragments(template).filter((f) => f.min > 0)
}

/** Show the Add/Delete fragment section only if at least one fragment has min > 0. */
export function hasConfigurableFragments(template?: CircuitTemplate): boolean {
return getConfigurableFragments(template).length > 0
}
