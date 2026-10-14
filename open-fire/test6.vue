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

interface FragmentTemplateLike {
id?: string
type?: string
multiplicity?: { min?: number; max?: number }
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
fragmentOperations?: Record<string, unknown>
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
