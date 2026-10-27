# PR 8604: Lachie's review comments, fixes and replies

Copy each **Reply** into the thread once the fix is pushed, then Resolve.

---

## 1. DynamicFragmentOperation.vue: "Please define concrete types" (`template: any`, `fragments?: any[]`)

**Fix**

```ts
import type { CircuitTemplate } from '@/api/generated/circuit-manager'
import type { CircuitFragmentInstance } from '../../utils/templateFragments'

const props = withDefaults(
  defineProps<{
    circuitId: string
    template: CircuitTemplate
    fragments?: CircuitFragmentInstance[]
  }>(),
  { fragments: () => [] },
)
```

**Reply:** Done. `template` is now `CircuitTemplate` and `fragments` is `CircuitFragmentInstance[]`.

---

## 2. CircuitDetails.vue: `loadCircuit()` with `console.log('Load circuit again')`
"Probably warrants a debug level and more context in the log message if this stays"

**Fix:** make it actually reload, and log at debug level with context:

```ts
async function loadCircuit() {
  console.debug(`[CircuitDetails] Reloading circuit ${props.circuitId} after fragment operation`)
  circuit.value = await getCircuit(props.circuitId)   // whatever onMounted already uses
}
```

(If the project has a logger, use that instead of `console.debug`.)

**Reply:** Changed to `console.debug` with the circuit id, and it now reloads the circuit so the fragment counts refresh after Add/Remove.

---

## 3. useUserAddressBook.ts: `list.push({ id: t.addressBookId!, label: t.name!, … })`
"Should we create a ticket to enforce that `addressBookId`, `name` etc are marked as required in the API?"

**Fix (now):** keep the `.filter((t) => t.addressBookId && t.name)` and use a type guard instead of `!`:

```ts
type CompleteEntry = AddressBook & { addressBookId: string; name: string }

teus
  ?.filter((t): t is CompleteEntry => !!t.addressBookId && !!t.name)
  .forEach((t) =>
    list.push({ id: t.addressBookId, label: t.name, extensionNumber: t.extensionNumber }),
  )
```

**Reply:** Agreed. I've raised a ticket to mark `addressBookId`, `name` (and `extensionNumber`) as required in the address-book API spec. For now the filter is a type guard, so the `!` assertions are gone. *(Add the ticket number.)*

---

## 4. templateFragments.ts: "Please add better response typing" / "Documentation on some/all of these functions would be valuable" / "Many `as any` can be removed"

**Fix:** already done in the updated `templateFragments.ts`: every export has JSDoc, return types are explicit, and there's no `as any`. It's built on the generated `CircuitTemplate` / `CircuitOperation` types plus `DynamicCapableFragmentTemplate` for the fields not in the schema yet.

**Reply:** Added JSDoc to every export and explicit return types. Removed all `as any`; the dynamic-fragment fields (`dynamicFragment`, `buildSteps`, `teardownSteps`) are typed in `DynamicCapableFragmentTemplate` until the schema is regenerated.

---

## 5. templateFragments.ts: `isDynamicFragment` "Can be `return fragment.dynamicFragment`. At that point, do we even need a utility function for this?"

## 6. templateFragments.ts: "Enforce non-null parameters" (`fragment?:` → `fragment:`)

**Fix**

```ts
export function isDynamicFragment(fragment: DynamicCapableFragmentTemplate): boolean {
  return !!fragment.dynamicFragment
}
```

**Reply (5):** Simplified as suggested. I'd keep it as a function since it's the single place that defines "dynamic". We used `multiplicity.max === -1` before the flag existed, so if the rule changes again it's one line. Happy to inline it if you'd rather.

**Reply (6):** Done, the parameter is now required.

---

## 7. PropertyFormInput.vue: "This component used to just be generic rendering of fields given a property, but now it has specific handling for PBX user selection. I think this should probably be split out into its own component"

**Fix:** new `src/components/common/PbxUserSelect.vue` (attached). It contains the `v-select`, the `useUserAddressBook()` options, the placeholder hiding and the required rule. `PropertyFormInput.vue` keeps only the check and delegates:

```vue
<PbxUserSelect
  v-if="isPbxUserProperty"
  v-model="localValue"
  :property="property"
/>
<v-text-field v-else-if="['string'].includes(property.type)" … />
```

Remove from `PropertyFormInput.vue`: the `v-select` block, `useUserAddressBook`, `userAddressBookValue`, `userRules`, `getParticipantIcon` import.

**Reply:** Split out into `PbxUserSelect.vue`. `PropertyFormInput` now only decides when to use it and passes `v-model` and `property` through.

---

## 8. PropertyFormInput.vue: `const p = props.property as any`, "No need to cast `as any`"
## 9. PropertyFormInput.vue: `(props.property as any)?.optional`, "A cast to `any` is a code smell, can we use a concrete type?"

**Fix:** `property` is already typed as `Property`, so use it directly:

```ts
const isPbxUserProperty = computed(
  () =>
    !!props.participantPicker &&
    ['array', 'string'].includes(props.property.type) &&
    PBX_USER_PROPERTY_NAMES.includes(props.property.name ?? props.propertyKey ?? ''),
)
```

`optional` now lives in `PbxUserSelect` as `props.property.optional`. If the generated `Property` type has no `optional`, add it to the property-service schema rather than casting.

**Reply:** Removed both casts. `Property` already has `type`, `name` and `optional`.

---

## 10. PropertyFormInput.vue: `` `Select at least one ${props.property.name ?? ' users'}` ``
Suggested change: `' users'` → `'user'`

**Fix:** applied in `PbxUserSelect.vue`: `` `Select at least one ${props.property.name ?? 'user'}` ``

**Reply:** Fixed, thanks.

---

## 11. PropertyFormInput.vue: `PARTICIPANT_PROPERTY_NAMES = ['targets', 'target']`
"If `targets` and `target` are reserved words to identify that the operation supports selecting PBX users, should we document this somewhere? e.g. in the API spec"

**Fix:** add a comment on the constant now:

```ts
/**
 * Operation parameters named `targets` / `target` are treated as PBX user selections
 * (address-book extension numbers) and rendered with PbxUserSelect.
 * Documented in the circuit-template spec. See <ticket/link>.
 */
const PBX_USER_PROPERTY_NAMES = ['targets', 'target']
```

**Reply:** Yes, agreed. I've added a comment on the constant and raised a ticket to document it in the circuit-template / API spec. Longer term a dedicated `format: pbx-user` (or similar) on the property would be more explicit than relying on the name. *(Add the ticket number.)*

---

## 12. requests.ts: `throw new Error('deleteFragment endpoint implemented yet')`
Suggested change: `implemented` → `not implemented`

**Fix:** `throw new Error('deleteFragment endpoint not implemented yet')`

**Reply:** Fixed.

---

## 13. "The pipeline appears to be failing"

**Cause:** the RolesService build failure. There are two axios versions in the workspace (1.13.6 and 1.20.0), causing TS2345 / TS2527 in `rolesService/common.ts`.

**Fix:** pin one axios version (root `pnpm.overrides`, or the same version in every module's `package.json`), then `pnpm install` and commit the updated `pnpm-lock.yaml`.

**Reply:** That was an axios version mismatch (1.13.6 vs 1.20.0) breaking the RolesService type-check. I've pinned a single version and the pipeline should be green now.
