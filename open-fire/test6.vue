/**
* Dynamic fragments and their circuit operations.
*
* Template (read-only, never modified here):
*   fragmentTemplates: [ { id: 'enmf', type: 'dynamic', … }, { id: 'pbx', … }, … ]
*   circuitOperations:
*     add-operator:    { fragmentOperations: { enmf: { … } } }   ← dynamic
*     delete-operator: { fragmentOperations: { enmf: { … } } }   ← dynamic
*     set-direction:   { fragmentOperations: { fragment1: { … } } }
*
* A circuit operation is "dynamic" if any key of its fragmentOperations is the id of a
* fragment template with type === 'dynamic'. Dynamic operations are shown in the
* Add/Delete fragment section, and the rest in Circuit operations.
*/

interface FragmentTemplateLike {
id?: string
type?: string
}

interface CircuitOperationLike {
fragmentOperations?: Record<string, unknown>
}

interface TemplateLike {
fragmentTemplates?: FragmentTemplateLike[]
circuitOperations?: Record<string, CircuitOperationLike>
}

/** ids of fragment templates with type: dynamic (e.g. ['enmf']). */
export function getDynamicFragmentIds(template?: unknown): Set<string> {
const fragments = (template as TemplateLike | undefined)?.fragmentTemplates ?? []
return new Set(
fragments.filter((f) => f.type === 'dynamic' && f.id).map((f) => f.id as string),
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


  =====

  import { getDynamicFragmentIds, isDynamicFragmentOperation } from '../../utils/templateFragments'

  const props = withDefaults(
  defineProps<{
  circuitId: string
  template: CircuitTemplate
  /** 'standard' = Circuit operations, 'dynamic' = Add/Delete fragment section */
  mode?: 'standard' | 'dynamic'
  }>(),
  { mode: 'standard' },
  )


  function getTableItems() {
  const items: CircuitOperationTableItem[] = []
  if (!props.template.circuitOperations) {
  return items
  }

  const dynamicFragmentIds = getDynamicFragmentIds(props.template)          // NEW

  Object.entries(props.template.circuitOperations).forEach(
  ([circuitOperationId, circuitOperation]) => {
  if (circuitOperationId.match(/^on-/)) return

  // NEW: dynamic ops only in the dynamic section, everything else only in the standard one
  const isDynamic = isDynamicFragmentOperation(circuitOperation, dynamicFragmentIds)
  if ((props.mode === 'dynamic') !== isDynamic) return

  items.push({
  id: circuitOperationId,
  name: circuitOperation.name || circuitOperationId,
  description: circuitOperation.description || '',
  fragmentOperations: circuitOperation.fragmentOperations || {},
  isConfigurable: isConfigurable(circuitOperation),
  result: OperationResult.NOT_RUN,
  loading: false,
  })
  },
  )

  return items
  }


  <v-expansion-panel v-if="showDynamicFragmentOperations">
    <v-expansion-panel-title>Add/Delete fragment</v-expansion-panel-title>
    <v-expansion-panel-text>
      <CircuitOperations
          :circuit-id="circuitId"
          :template="template"
          mode="dynamic"
      />
    </v-expansion-panel-text>
  </v-expansion-panel>


  import { hasDynamicFragmentOperations } from '../utils/templateFragments'

  const showDynamicFragmentOperations = computed(() => hasDynamicFragmentOperations(template.value))
