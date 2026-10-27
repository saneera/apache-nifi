/**
 * Helpers for dynamic fragments in a circuit template.
 *
 * A fragment template is "dynamic" when it has `dynamicFragment: true`. Instances of it can be
 * added to / removed from a running circuit. The circuit operations that act on a dynamic
 * fragment are shown in the Fragment Operations section instead of Circuit operations.
 *
 * Example (MAS6_RM12 template):
 * ```yaml
 * fragmentTemplates:
 *   - id: enmf
 *     dynamicFragment: true
 *     buildSteps:    [{ elementId: pbx, operationId: add-operator }]     # → create
 *     teardownSteps: [{ elementId: pbx, operationId: delete-operator }]  # → delete
 * circuitOperations:
 *   add-operator:    { fragmentOperations: { enmf: { headset: [...] } } }
 *   delete-operator: { fragmentOperations: { enmf: { headset: [...] } } }
 * ```
 *
 * The template is only read here, never modified.
 */
import type {
  CircuitFragmentTemplate,
  CircuitOperation,
  CircuitTemplate,
} from '@/api/generated/circuit-manager'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** A build / teardown step on a fragment template. */
export interface FragmentStep {
  elementId?: string
  /** id of the circuit operation run by this step, e.g. `'add-operator'` */
  operationId?: string
}

/**
 * Fragment template with the dynamic-fragment fields.
 * `dynamicFragment`, `buildSteps` and `teardownSteps` are declared here because they may not
 * be in the generated `CircuitFragmentTemplate` yet. Remove this once the schema is regenerated.
 */
export type DynamicCapableFragmentTemplate = Omit<
  CircuitFragmentTemplate,
  'buildSteps' | 'teardownSteps'
> & {
  dynamicFragment?: boolean
  buildSteps?: FragmentStep[]
  teardownSteps?: FragmentStep[]
}

/** Circuit template with its fragment templates typed as {@link DynamicCapableFragmentTemplate}. */
export type DynamicCapableCircuitTemplate = Omit<CircuitTemplate, 'fragmentTemplates'> & {
  fragmentTemplates?: DynamicCapableFragmentTemplate[]
}

/** `fragmentOperations` of a circuit operation: fragment id → element id → statements. */
export type FragmentOperations = NonNullable<CircuitOperation['fragmentOperations']>

/**
 * What a dynamic fragment operation does:
 * - `create`: listed in the fragment's `buildSteps`. Adds a new instance (no instance picker).
 * - `delete`: listed in the fragment's `teardownSteps`. Removes an existing instance.
 * - `operate`: targets the fragment but is neither. Acts on an existing instance.
 */
export type DynamicOperationKind = 'create' | 'delete' | 'operate'

/** A circuit operation that acts on a dynamic fragment, as shown in Fragment Operations. */
export interface DynamicFragmentOperation {
  /** Circuit operation id, e.g. `'add-operator'`. */
  id: string
  /** Display name from the template (falls back to the id). */
  name: string
  description: string
  /** Id of the dynamic fragment template it acts on, e.g. `'enmf'`. */
  fragmentId: string
  kind: DynamicOperationKind
  /** The operation's `fragmentOperations`, used to build the form and the request. */
  fragmentOperations: FragmentOperations
}

/** One dynamic fragment template and the operations that act on it. */
export interface DynamicFragmentGroup {
  /** Fragment template id, e.g. `'enmf'`. */
  fragmentId: string
  /** Display name, e.g. `'ENMF'` (falls back to the id). */
  name: string
  /** Sorted: create, then operate, then delete. */
  operations: DynamicFragmentOperation[]
}

/**
 * A fragment instance on a circuit (an entry of `circuit.fragments`).
 * Only the fields used here; the template-id field name isn't confirmed yet,
 * so both candidates are optional.
 */
export interface CircuitFragmentInstance {
  id: string
  name?: string
  templateId?: string
  fragmentTemplateId?: string
}

/** An option in the Instance dropdown. */
export interface FragmentInstanceOption {
  /** Instance id on the circuit, sent to delete / operate. */
  id: string
  /** Label shown in the dropdown. */
  label: string
}

/** Result of {@link getOperationAvailability}. */
export interface OperationAvailability {
  allowed: boolean
  /** Why the operation can't run, shown under the Configure button. */
  reason?: string
}

// ---------------------------------------------------------------------------
// Template helpers
// ---------------------------------------------------------------------------

/**
 * Whether a fragment template is dynamic (`dynamicFragment: true`).
 *
 * @param fragment fragment template from `template.fragmentTemplates`
 * @returns `true` when the flag is set
 */
export function isDynamicFragment(fragment: DynamicCapableFragmentTemplate): boolean {
  return !!fragment.dynamicFragment
}

/**
 * The template's dynamic fragment templates (with an id).
 *
 * @param template circuit template
 * @returns dynamic fragment templates, or `[]` if there are none
 */
function getDynamicFragmentTemplates(
  template?: CircuitTemplate,
): (DynamicCapableFragmentTemplate & { id: string })[] {
  const fragments = (template as DynamicCapableCircuitTemplate | undefined)?.fragmentTemplates ?? []
  return fragments.filter(
    (f): f is DynamicCapableFragmentTemplate & { id: string } => !!f.id && isDynamicFragment(f),
  )
}

/**
 * Ids of the template's dynamic fragments.
 *
 * @param template circuit template
 * @returns e.g. `Set { 'enmf' }`
 */
export function getDynamicFragmentIds(template?: CircuitTemplate): Set<string> {
  return new Set(getDynamicFragmentTemplates(template).map((f) => f.id))
}

/**
 * Whether a circuit operation acts on a dynamic fragment, i.e. one of the keys of its
 * `fragmentOperations` is a dynamic fragment id. Used by Circuit operations to hide these.
 *
 * @param operation circuit operation from `template.circuitOperations`
 * @param dynamicFragmentIds result of {@link getDynamicFragmentIds}
 */
export function isDynamicFragmentOperation(
  operation: CircuitOperation | undefined,
  dynamicFragmentIds: Set<string>,
): boolean {
  return Object.keys(operation?.fragmentOperations ?? {}).some((fragmentId) =>
    dynamicFragmentIds.has(fragmentId),
  )
}

/**
 * Whether the Fragment Operations section should be shown.
 *
 * @param template circuit template
 * @returns `true` if at least one circuit operation acts on a dynamic fragment
 */
export function hasDynamicFragmentOperations(template?: CircuitTemplate): boolean {
  return getDynamicFragmentGroups(template).length > 0
}

/** Sort order within a group: Add above other operations, Remove last. */
const KIND_ORDER: Record<DynamicOperationKind, number> = { create: 0, operate: 1, delete: 2 }

/**
 * Groups the circuit operations that act on dynamic fragments, one group per fragment.
 *
 * Each operation is placed in exactly one group:
 * 1. the fragment whose `buildSteps` name it → `create`
 * 2. else the fragment whose `teardownSteps` name it → `delete`
 * 3. else the first dynamic fragment in its `fragmentOperations` → `operate`
 *
 * @param template circuit template
 * @returns groups that have at least one operation, in template order
 */
export function getDynamicFragmentGroups(template?: CircuitTemplate): DynamicFragmentGroup[] {
  const fragments = getDynamicFragmentTemplates(template)
  const fragmentIds = new Set(fragments.map((f) => f.id))
  const operations: Record<string, CircuitOperation> = template?.circuitOperations ?? {}

  const groups = new Map<string, DynamicFragmentGroup>(
    fragments.map((f) => [f.id, { fragmentId: f.id, name: f.name || f.id, operations: [] }]),
  )

  /** The dynamic fragment whose build / teardown steps run this operation, if any. */
  const findStepOwner = (operationId: string, steps: 'buildSteps' | 'teardownSteps') =>
    fragments.find((f) => (f[steps] ?? []).some((s) => s.operationId === operationId))

  Object.entries(operations).forEach(([id, operation]) => {
    const builder = findStepOwner(id, 'buildSteps')
    const tearer = findStepOwner(id, 'teardownSteps')

    let fragmentId: string | undefined
    let kind: DynamicOperationKind
    if (builder) {
      fragmentId = builder.id
      kind = 'create'
    } else if (tearer) {
      fragmentId = tearer.id
      kind = 'delete'
    } else {
      fragmentId = Object.keys(operation.fragmentOperations ?? {}).find((k) => fragmentIds.has(k))
      kind = 'operate'
    }
    if (!fragmentId) return

    groups.get(fragmentId)?.operations.push({
      id,
      name: operation.name || id,
      description: operation.description || '',
      fragmentId,
      kind,
      fragmentOperations: operation.fragmentOperations ?? {},
    })
  })

  groups.forEach((g) => g.operations.sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind]))

  return [...groups.values()].filter((g) => g.operations.length > 0)
}

/**
 * All dynamic fragment operations as a flat list.
 *
 * @param template circuit template
 */
export function getDynamicFragmentOperations(
  template?: CircuitTemplate,
): DynamicFragmentOperation[] {
  return getDynamicFragmentGroups(template).flatMap((g) => g.operations)
}

// ---------------------------------------------------------------------------
// Circuit instance helpers
// ---------------------------------------------------------------------------

/**
 * Template id of a fragment instance on the circuit.
 * TODO: confirm the field name on the generated `Fragment` type and drop the fallback.
 *
 * @param fragment entry of `circuit.fragments`
 */
export function getInstanceTemplateId(fragment: CircuitFragmentInstance): string | undefined {
  return fragment.templateId ?? fragment.fragmentTemplateId
}

/**
 * Instances of one dynamic fragment currently on the circuit, as dropdown options.
 *
 * @param circuitFragments `circuit.fragments`
 * @param group the dynamic fragment (only `fragmentId` and `name` are used)
 * @returns options labelled with the instance name, or `"<FRAGMENT> #n"` if it has none
 */
export function getFragmentInstances(
  circuitFragments: CircuitFragmentInstance[] | undefined,
  group: Pick<DynamicFragmentGroup, 'fragmentId' | 'name'>,
): FragmentInstanceOption[] {
  return (circuitFragments ?? [])
    .filter((f) => getInstanceTemplateId(f) === group.fragmentId)
    .map((f, i) => ({ id: f.id, label: f.name || `${group.name} #${i + 1}` }))
}

/**
 * Whether an operation can be run. No multiplicity (min/max) limits are enforced;
 * `delete` / `operate` only need an existing instance to pick.
 *
 * @param kind operation kind
 * @param instanceCount number of instances of the fragment on the circuit
 */
export function getOperationAvailability(
  kind: DynamicOperationKind,
  instanceCount: number,
): OperationAvailability {
  if (kind !== 'create' && instanceCount === 0) {
    return {
      allowed: false,
      reason: kind === 'delete' ? 'Nothing to remove yet' : 'No instances yet',
    }
  }
  return { allowed: true }
}
