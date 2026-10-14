<!--
  Fragment Operations: circuit operations that act on a dynamic fragment
  (e.g. add-operator / delete-operator on `enmf`).

  Differences from CircuitOperations.vue:
    - no search / result filter / run all / reset
    - Run calls the fragment endpoints:
        create (fragment buildSteps)    → POST createFragment  { name: fragmentId, request }
        delete (fragment teardownSteps) → deleteFragment       (TODO: endpoint)
    - targets shows the participant dropdown (participant-picker)
-->
<template>
  <v-data-table-virtual
      id="dynamic-fragment-operations-table"
      v-model:expanded="expandedRows"
      :headers="headers"
      :items="items"
      item-value="id"
      density="compact"
  >
    <template #item.data-table-expand="{ item }">
      <v-btn
          :id="`dynamic-fragment-operations-configure-${item.id}`"
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
          <v-form
              :ref="(el) => (forms[item.id] = el)"
              class="py-2"
              @submit.prevent="run(item)"
          >
            <template v-for="(elements, fragmentId) in item.fragmentOperations" :key="fragmentId">
              <template v-for="(statements, elementId) in elements" :key="elementId">
                <v-row class="ml-1 mr-2 mt-2 mb-1" align="center">
                  {{ elementId }}
                  <v-chip class="ml-2" size="x-small">{{ fragmentId }}</v-chip>
                </v-row>

                <template v-for="(statement, i) in statements" :key="i">
                  <PropertyFormInput
                      v-if="'property' in statement && circuitProperties[statement.property]"
                      v-model="formData[item.id][`${elementId}.${statement.property}`]"
                      :property="circuitProperties[statement.property].args"
                      participant-picker
                  />
                  <PropertyFormInput
                      v-if="'command' in statement && circuitProperties[statement.command]"
                      v-model="formData[item.id][`${elementId}.${statement.command}`]"
                      :property="circuitProperties[statement.command].args"
                      participant-picker
                  />
                </template>
              </template>
            </template>

            <v-row class="mt-2 mb-1 mr-1">
              <v-spacer />
              <v-btn
                  :id="`dynamic-fragment-operations-cancel-${item.id}`"
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
                  :id="`dynamic-fragment-operations-run-${item.id}`"
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
import { computed, ref, toRaw, watch } from 'vue'

import { FormData } from '@/types/FormData'
import { createFragment, deleteFragment } from '../../api/requests'
import { OperationResult } from '../../types/OperationResult'
import { createCircuitOperationRequest } from '../../utils/CircuitOperationRequest'
import {
  DynamicFragmentOperation,
  getDynamicFragmentOperations,
} from '../../utils/templateFragments'
import PropertyFormInput from '../common/PropertyFormInput.vue'

const props = defineProps<{
  circuitId: string
  template: any // CircuitTemplate. Typed loosely: fragmentTemplates isn't in the generated type
}>()

const emit = defineEmits<{ updated: [] }>()

interface Row extends DynamicFragmentOperation {
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

const circuitProperties = computed<Record<string, any>>(
    () => props.template?.circuitProperties ?? {},
)

const items = ref<Row[]>([])
const formData = ref<Record<string, FormData>>({})
const forms = ref<Record<string, any>>({})
const expandedRows = ref<string[]>([])

// Rebuild when anything the list depends on changes, including when the template is
// filled in after mount (a plain watch on props.template only fires on a new object).
watch(
    () => getDynamicFragmentOperations(props.template),
    (operations) => {
      items.value = operations.map((op) => ({
        ...op,
        result: OperationResult.NOT_RUN,
        loading: false,
      }))
      formData.value = Object.fromEntries(
          operations.map((op) => [op.id, initialFormData(op.fragmentOperations)]),
      )
    },
    { immediate: true },
)

/**
 * Start each field from the template's values, keyed like CircuitOperationForm
 * (`${elementId}.${property|command}`). Properties take `value`, commands take `parameters`.
 */
function initialFormData(fragmentOperations: Record<string, any>): FormData {
  const data: FormData = {}
  Object.values(fragmentOperations).forEach((elements: any) => {
    Object.entries(elements ?? {}).forEach(([elementId, statements]: [string, any]) => {
      ;(statements ?? []).forEach((statement: any) => {
        if ('property' in statement) {
          data[`${elementId}.${statement.property}`] = clone(statement.value)
        } else if ('command' in statement) {
          data[`${elementId}.${statement.command}`] = clone(statement.parameters)
        }
      })
    })
  })
  return data
}

/** Plain copy of template values (structuredClone throws on Vue reactive proxies). */
function clone<T>(value: T): T {
  return value === undefined ? value : JSON.parse(JSON.stringify(toRaw(value)))
}

function toggleExpandedRow(id: string) {
  expandedRows.value = expandedRows.value.includes(id)
      ? expandedRows.value.filter((r) => r !== id)
      : [...expandedRows.value, id]
}

async function run(item: Row) {
  const { valid } = (await forms.value[item.id]?.validate()) ?? { valid: true }
  if (!valid) return

  item.loading = true
  try {
    // Same CircuitOperationRequest shape as circuit operations: { fragment: { element: {…} } }
    const request = createCircuitOperationRequest(item.fragmentOperations, formData.value[item.id])

    const status =
        item.kind === 'create'
            ? await createFragment(props.circuitId, item.fragmentId, request)
            : await deleteFragment(props.circuitId, item.fragmentId, request)

    item.result = status >= 200 && status < 300 ? OperationResult.SUCCESS : OperationResult.FAILURE

    if (item.result === OperationResult.SUCCESS) {
      toggleExpandedRow(item.id)
      emit('updated')
    }
  } catch (error: any) {
    item.result = OperationResult.FAILURE
    notificationStore.error(`${item.name} failed: ${error.message}`)
    console.error(error)
  } finally {
    item.loading = false
  }
}

// Same chip look as CircuitOperations.vue. Swap for its helpers if they're exported.
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
