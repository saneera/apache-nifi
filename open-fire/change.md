# Fragment Operations section (separate component)

## Rule

- **Dynamic fragment** = fragment template with `multiplicity.max === -1` (for now; `type`
  isn't in the API response). In MAS6_RM12_VOICE: `enmf`.
- **Dynamic operation** = circuit operation whose `fragmentOperations` targets a dynamic fragment.
  In MAS6_RM12_VOICE: `add-operator`, `delete-operator`.
- Dynamic operations are **removed from Circuit operations** and shown in **Fragment Operations**.
- **create vs delete:** if the operation is in the fragment's `teardownSteps` it's a delete,
  otherwise it's a create (`buildSteps`). For `enmf`: add-operator = create, delete-operator = delete.

## Files

| File | Change |
|---|---|
| `src/components/operations/DynamicFragmentOperations.vue` | **New.** Table + form + Run |
| `src/utils/templateFragments.ts` | **Updated.** Adds `getDynamicFragmentOperations()` |
| `src/components/common/PropertyFormInput.vue` | **Updated.** `command` prop → `participantPicker` |
| `CircuitOperationForm.vue` | Remove `:command="statement.command"` (prop no longer exists) |
| `CircuitOperations.vue` | Filter dynamic ops out (no `mode` prop needed) |
| `CircuitDetails.vue` | Panel uses `DynamicFragmentOperations` |
| `requests.ts` | `createFragment` (+ `deleteFragment` stub) |

---

## 1. Why targets wasn't showing

In the template, `add-operator`'s statement is `command: conference-compose`. The old check
looked for the **command** being `add-operator`/`delete-operator`, so it never matched.
`PropertyFormInput` now takes a boolean `participantPicker`, set only by the new component,
so it no longer depends on command names.
`conference-compose` in Circuit operations doesn't set it, so `targets` stays hidden there.

## 2. CircuitOperationForm.vue

Remove the line added earlier:

```vue
:command="statement.command"     ← delete
```

## 3. CircuitOperations.vue: filter dynamic ops out

Drop the `mode` prop if you added it. In `getTableItems()`:

```ts
import { getDynamicFragmentIds, isDynamicFragmentOperation } from '../../utils/templateFragments'

const dynamicFragmentIds = getDynamicFragmentIds(props.template)

Object.entries(props.template.circuitOperations).forEach(([circuitOperationId, circuitOperation]) => {
  if (circuitOperationId.match(/^on-/)) return
  if (isDynamicFragmentOperation(circuitOperation, dynamicFragmentIds)) return   // NEW
  items.push({ … })
})
```

## 4. CircuitDetails.vue

```vue
<v-expansion-panel v-if="showFragmentOperations">
  <v-expansion-panel-title>Fragment Operations</v-expansion-panel-title>
  <v-expansion-panel-text>
    <DynamicFragmentOperations
      :circuit-id="circuitId"
      :template="template"
      @updated="loadCircuit"
    />
  </v-expansion-panel-text>
</v-expansion-panel>
```

```ts
import DynamicFragmentOperations from './operations/DynamicFragmentOperations.vue'
import { hasDynamicFragmentOperations } from '../utils/templateFragments'

const showFragmentOperations = computed(() => hasDynamicFragmentOperations(template.value))
```

`@updated` → whatever reloads the circuit (or remove it).

## 5. requests.ts

```ts
/** POST createFragment: FragmentRequest { name, request } */
export async function createFragment(
  circuitId: string,
  fragmentName: string,
  request: CircuitOperationRequest = {},
): Promise<number> {
  // Same client / style as performCircuitOperation. Use the real path from circuit-manager.yaml.
  const { response } = await client.POST('/circuits/{circuit}/fragments', {
    params: { path: { circuit: circuitId } },
    body: { name: fragmentName, request } as FragmentRequest,
  })
  return response.status
}

/** TODO: no delete-fragment endpoint seen yet */
export async function deleteFragment(
  circuitId: string,
  fragmentName: string,
  request: CircuitOperationRequest = {},
): Promise<number> {
  throw new Error('deleteFragment endpoint not wired yet')
}
```

Payload sent by Add operator (example):

```json
{
  "name": "enmf",
  "request": {
    "enmf": {
      "headset": {
        "conference-compose": {
          "conferenceId": "conference-compose.result.result.body.conferenceId",
          "targets": ["<selected participant id>"]
        }
      }
    }
  }
}
```

(The inner shape is whatever your existing `createCircuitOperationRequest()` builds, the
same as circuit operations.)

---

## Open

- **Delete endpoint:** what should Remove operator call? Is there a `DELETE …/fragments/{id}`,
  and does it need the fragment instance id?
- **`FragmentRequest.name`:** sends the fragment template id (`enmf`). The schema says
  "shall match the fragment name". If it means `name: ENMF`, change `item.fragmentId` in `run()`.
- **`FragmentRequest` `required: [type]`** has no matching property; cast covers it for now.
