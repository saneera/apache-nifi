/**
* From circuit-template.yaml:
*   CircuitFragmentTemplate { id, name, nodeType, multiplicity: FragmentMultiplicity, … }
*   FragmentMultiplicity    { min?: integer, max?: integer }
* multiplicity.min > 0 means the fragment is dynamic (can be added / deleted).
*
* Uses local structural types so it doesn't depend on which generated schema
* file exports FragmentMultiplicity. It accepts any template object with that shape.
*/

interface FragmentMultiplicityLike {
min?: number
max?: number
}

interface FragmentTemplateLike {
id?: string
name?: string
multiplicity?: FragmentMultiplicityLike
}

export interface TemplateFragment {
id: string
name: string
min: number
max?: number
}

/**
* All fragment templates in the circuit template.
* TODO: confirm the property on CircuitTemplate that holds the fragment templates
* (assumed `fragments`). Works whether it's an array or a map keyed by id.
*/
export function getTemplateFragments(template?: unknown): TemplateFragment[] {
const raw = (template as { fragments?: unknown } | undefined)?.fragments
if (!raw) return []

const list: [string, FragmentTemplateLike][] = Array.isArray(raw)
? (raw as FragmentTemplateLike[]).map((f) => [f.id ?? '', f])
: Object.entries(raw as Record<string, FragmentTemplateLike>)

return list.map(([key, f]) => ({
id: f.id ?? key,
name: f.name ?? f.id ?? key,
min: f.multiplicity?.min ?? 0,
max: f.multiplicity?.max,
}))
}

/** Dynamic = multiplicity.min > 0. */
export function isDynamic(multiplicity?: FragmentMultiplicityLike): boolean {
return (multiplicity?.min ?? 0) > 0
}

/** Dynamic fragments, i.e. those that can be added / deleted. */
export function getConfigurableFragments(template?: unknown): TemplateFragment[] {
return getTemplateFragments(template).filter((f) => isDynamic(f))
}

/** Show the Add/Delete fragment section only if the template has a dynamic fragment. */
export function hasConfigurableFragments(template?: unknown): boolean {
return getConfigurableFragments(template).length > 0
}


=========


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
                hint="Dynamic fragments (multiplicity min > 0)"
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
      value: f.id,
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
