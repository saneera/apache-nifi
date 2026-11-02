// ===== 1. Router: add next to the other Circuits child routes =====
// (same file / structure as CircuitCreation, CircuitExecution, CircuitTemplates, CircuitInitialisation)
{
  path: 'conflicts',                      // or '/circuits/conflicts' if routes are flat
  name: 'CircuitConflicts',
  component: () => import('../views/CircuitConflicts.vue'),
},

// ===== 2. Side menu: add under Circuits, after Initialisation =====
// Copy the shape of the existing "Initialisation" entry; e.g.
{
  title: 'Conflicts',
  icon: 'mdi-alert-octagon-outline',
  to: { name: 'CircuitConflicts' },
},

// ===== 3. API: add next to postCircuit / performCircuitAction =====
// Use the generated client the same way the other functions in that file do, e.g.:
export async function getConflictsList(): Promise<CircuitConflicts[]> {
  // return (await conflictsApi.getConflictsList()).data      // axios-style generated client
  // return await ConflictsService.getConflictsList()         // openapi-typescript-codegen style
}

// Also for the circuit detail view drop-down (the Jira AC):
export async function getCircuitConflicts(circuitId: string): Promise<CircuitConflicts> {
  // return (await circuitsApi.getCircuitConflicts(circuitId)).data
}

// ===== 4. Optional: reuse the table in the circuit detail view (Jira AC) =====
// <v-expansion-panels>
//   <v-expansion-panel title="Conflicts">
//     <v-expansion-panel-text>
//       <CircuitConflictsTable :conflicts="circuitConflicts ? [circuitConflicts] : []"
//                              :show-circuit-column="false" />
//     </v-expansion-panel-text>
//   </v-expansion-panel>
// </v-expansion-panels>
