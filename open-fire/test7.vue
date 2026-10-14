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
import { computed, ref, watch } from 'vue'

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

watch(
    () => props.template,
    (template) => {
      items.value = getDynamicFragmentOperations(template).map((op) => ({
        ...op,
        result: OperationResult.NOT_RUN,
        loading: false,
      }))
      formData.value = Object.fromEntries(
          items.value.map((op) => [op.id, initialFormData(op.fragmentOperations)]),
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
          data[`${elementId}.${statement.property}`] = structuredClone(statement.value)
        } else if ('command' in statement) {
          data[`${elementId}.${statement.command}`] = structuredClone(statement.parameters)
        }
      })
    })
  })
  return data
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


============

<!--
  Reconstructed from the screen recording. Diff it against your copy rather
  than overwriting blind. Every change is marked NEW.
-->
<template>
  <!-- NEW: participant picker for `targets`, the same list as the
       circuit-creation Participants dropdown (CircuitParticipants.vue).
       Placed first so it wins over the plain string/array branches. -->
  <v-select
      v-if="isParticipantProperty"
      :id="`property-form-input-local-value-${Math.floor(Math.random() * 1000)}`"
      v-model="participantValue"
      :items="participantOptions"
      item-title="label"
      item-value="id"
      :label="property.name"
      :hint="property.description"
      :multiple="property.type === 'array'"
      :chips="property.type === 'array'"
      :loading="participantsLoading"
      no-data-text="No participants found"
      clearable
      persistent-hint
      variant="solo"
      density="compact"
  >
    <template #chip="{ item }">
      <v-chip color="primary">
        <v-icon :icon="getParticipantIcon(item.raw.type)" start />
        {{ item.raw.label }}
      </v-chip>
    </template>
  </v-select>

  <v-text-field
      v-else-if="['string'].includes(property.type)"
      :id="`property-form-input-local-value-${Math.floor(Math.random() * 1000)}`"
      v-model="localValue"
      :label="property.name"
      :hint="property.description"
      density="compact"
  />

  <v-text-field
      v-else-if="['integer', 'float'].includes(property.type)"
      :id="`property-form-input-local-value-${Math.floor(Math.random() * 1000)}`"
      v-model.number="localValue"
      :label="property.name"
      :hint="property.description"
      density="compact"
  />

  <v-select
      v-else-if="property.type === 'enum'"
      :id="`property-form-input-local-value-${Math.floor(Math.random() * 1000)}`"
      v-model="localValue"
      :items="[...enumValOptions, '$property']"
      :label="property.name"
      :hint="property.description"
      density="compact"
  />

  <v-select
      v-else-if="property.type === 'boolean'"
      :id="`property-form-input-local-value-${Math.floor(Math.random() * 1000)}`"
      v-model="localValue"
      :items="['true', 'false', '$property']"
      :label="property.name"
      :hint="property.description"
      density="compact"
      color="primary"
  />

  <div v-if="property.type === 'object'" style="width: 100%">
    <div v-for="(childProp, key) in properties" :key="key">
      <PropertyFormInput
          :model-value="localValue[key]"
          :property="childProp"
          :participant-picker="participantPicker"
          @update:model-value="(val) => updateNestedValue(key, val)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  Property,
  PropertyEnum,
  PropertyObject,
} from '../../api/generated/property-service'
import {
  getParticipantIcon,
  useParticipantDirectory,
} from '../../composables/useParticipantDirectory' // NEW

const props = defineProps<{
  modelValue: any
  property: Property
  /**
   * NEW: show the participant dropdown for `targets`. Set by DynamicFragmentOperations.vue
   * only; Circuit operations leaves it off, so targets stays hidden there.
   */
  participantPicker?: boolean
}>()

const emit = defineEmits(['update:modelValue'])

const enumValOptions = ref<string[]>([])
const properties = ref<Record<string, Property>>({})

onMounted(() => {
  if (props.property.type === 'enum') {
    const propEnum = props.property as PropertyEnum
    enumValOptions.value = propEnum.values ?? []
  } else if (props.property.type === 'object') {
    const propObject = props.property as PropertyObject
    properties.value = propObject.properties
  }
})

// ---------- NEW: participant dropdown ----------

/** Property names whose values are participant ids (meu id / address-book id). */
const PARTICIPANT_PROPERTY_NAMES = ['targets', 'target']

const isParticipantProperty = computed(() => {
  const p = props.property as any
  // All must match: picker enabled, an array named targets/target, items are address-book ids
  return (
      !!props.participantPicker &&
      p.type === 'array' &&
      PARTICIPANT_PROPERTY_NAMES.includes(p.name) &&
      /address\s?book/i.test(p.items?.description ?? '')
  )
})

/** Same MEUs + TEUs as the circuit-creation Participants dropdown. */
const { directory: participantOptions, loading: participantsLoading } =
    useParticipantDirectory()

/**
 * Only show values that match a real participant. This hides template
 * placeholders such as the YAML default "$USER_ADDRESSBOOK_ID", so nothing is pre-ticked.
 */
const participantValue = computed({
  get() {
    const ids = new Set(participantOptions.value.map((p) => p.id))
    const v = localValue.value
    if (Array.isArray(v)) return v.filter((x) => ids.has(x))
    return ids.has(v) ? v : null
  },
  set(value) {
    localValue.value = value ?? (props.property.type === 'array' ? [] : null)
  },
})

// ---------- end NEW ----------

const localValue = computed({
  get() {
    const type = props.property.type
    const value = props.modelValue

    if (type === 'object') {
      return typeof value === 'object' && value ? value : {}
    }

    // NEW: arrays (e.g. targets) stay arrays; a single value is wrapped
    if (type === 'array') {
      if (Array.isArray(value)) return value
      return value == null || value === '' ? [] : [value]
    }

    if (type === 'string') {
      return typeof value === 'string' ? value : ''
    }

    if (type === 'integer' || type === 'float') {
      const raw = value && typeof value === 'object' ? extractValue(value) : value
      const n = Number(raw)
      if (raw == null || raw === '' || Number.isNaN(n)) {
        return null
      }
      return type === 'integer' ? Math.trunc(n) : n
    }

    return value
  },
  set(value) {
    if (props.property.type !== 'object') {
      emit('update:modelValue', value)
    }
  },
})

function updateNestedValue(key: string | number, val: any) {
  const currentValue = props.modelValue
  const updatedValue =
      typeof currentValue === 'object' && currentValue !== null ? { ...currentValue } : {}
  updatedValue[key] = val
  emit('update:modelValue', updatedValue)
}

const extractValue = (value: object) => {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const entries = Object.entries(value)
    if (entries.length === 1) {
      return entries[0][1]
    }
  }
  return value
}
</script>


===============


/**
* Dynamic fragments and their circuit operations.
*
* Template (read-only, never modified here):
*   fragmentTemplates: [ { id: 'enmf', multiplicity: { min: 0, max: -1 } }, { id: 'pbx', … }, … ]
*   circuitOperations:
*     add-operator:    { fragmentOperations: { enmf: { … } } }   ← dynamic
*     delete-operator: { fragmentOperations: { enmf: { … } } }   ← dynamic
*     set-direction:   { fragmentOperations: { fragment1: { … } } }
*
* A circuit operation is "dynamic" if any key of its fragmentOperations is the id of a
* dynamic fragment template (see isDynamicFragment). Dynamic operations are shown in the
* Add/Delete fragment section, and the rest in Circuit operations.
*/

interface StepLike {
elementId?: string
operationId?: string
}

interface FragmentTemplateLike {
id?: string
name?: string
type?: string
multiplicity?: { min?: number; max?: number }
buildSteps?: StepLike[]
teardownSteps?: StepLike[]
}

/**
* Dynamic fragment check.
* For now: multiplicity.max === -1 (unlimited instances). The API response doesn't
* include `type`, so `type === 'dynamic'` can't be used yet. Swap this one function
* when it does.
*/
export function isDynamicFragment(fragment: FragmentTemplateLike): boolean {
return fragment.multiplicity?.max === -1
}

interface CircuitOperationLike {
name?: string
description?: string
fragmentOperations?: Record<string, any>
}

interface TemplateLike {
fragmentTemplates?: FragmentTemplateLike[]
circuitOperations?: Record<string, CircuitOperationLike>
}

/** ids of dynamic fragment templates (e.g. ['enmf']). */
export function getDynamicFragmentIds(template?: unknown): Set<string> {
const fragments = (template as TemplateLike | undefined)?.fragmentTemplates ?? []
return new Set(
fragments.filter((f) => f.id && isDynamicFragment(f)).map((f) => f.id as string),
)
}

/** True if the operation runs against a dynamic fragment. */
export function isDynamicFragmentOperation(
operation: CircuitOperationLike | undefined,
dynamicFragmentIds: Set<string>,
  ): boolean {
  return Object.keys(operation?.fragmentOperations ?? {}).some((fragmentId) =>
  dynamicFragmentIds.has(fragmentId),
  )
  }

  /** Show the Add/Delete fragment section only if at least one operation is dynamic. */
  export function hasDynamicFragmentOperations(template?: unknown): boolean {
  const ids = getDynamicFragmentIds(template)
  if (!ids.size) return false
  const ops = (template as TemplateLike | undefined)?.circuitOperations ?? {}
  return Object.values(ops).some((op) => isDynamicFragmentOperation(op, ids))
  }

  export type DynamicOperationKind = 'create' | 'delete'

  export interface DynamicFragmentOperation {
  /** circuit operation id, e.g. 'add-operator' */
  id: string
  name: string
  description: string
  /** dynamic fragment it acts on, e.g. 'enmf' (sent as FragmentRequest.name) */
  fragmentId: string
  /** create = in the fragment's buildSteps, delete = in its teardownSteps */
  kind: DynamicOperationKind
  fragmentOperations: Record<string, any>
  }

  /**
  * Circuit operations that act on a dynamic fragment, for the Fragment Operations section.
  * kind comes from the fragment template: an operation listed in teardownSteps is a delete,
  * and anything else (normally buildSteps) is a create.
  */
  export function getDynamicFragmentOperations(template?: unknown): DynamicFragmentOperation[] {
  const t = template as TemplateLike | undefined
  const fragments = (t?.fragmentTemplates ?? []).filter((f) => f.id && isDynamicFragment(f))
  const byId = new Map(fragments.map((f) => [f.id as string, f]))

  return Object.entries(t?.circuitOperations ?? {}).flatMap(([id, op]) => {
  const fragmentId = Object.keys(op?.fragmentOperations ?? {}).find((k) => byId.has(k))
  if (!fragmentId) return []

  const fragment = byId.get(fragmentId)!
  const isTeardown = (fragment.teardownSteps ?? []).some((s) => s.operationId === id)

  return [
  {
  id,
  name: op.name || id,
  description: op.description || '',
  fragmentId,
  kind: isTeardown ? 'delete' : 'create',
  fragmentOperations: op.fragmentOperations ?? {},
  } as DynamicFragmentOperation,
  ]
  })
  }
