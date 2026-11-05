<script setup lang="ts">
import { computed } from 'vue'
import type { FlowStatus } from '../api'
import { useWizard } from '../stores/wizard'
import Icon from '../components/ui/Icon.vue'

const w = useWizard()

const counts = computed(() => ({
  NEW: w.flows.filter((f) => f.status === 'NEW').length,
  CHANGED: w.flows.filter((f) => f.status === 'CHANGED').length,
  UNCHANGED: w.flows.filter((f) => f.status === 'UNCHANGED').length,
}))

const BADGE = { NEW: 'pill-info', CHANGED: 'pill-warn', UNCHANGED: 'pill-neutral' } as const
const LABEL = { NEW: 'New', CHANGED: 'Changed', UNCHANGED: 'Unchanged' } as const

function versionText(f: FlowStatus, on: boolean) {
  if (f.registryVersion == null) return 'create → v1'
  if (f.status === 'UNCHANGED' && !on) return `v${f.registryVersion} · skip`
  return `v${f.registryVersion} → v${f.registryVersion + 1}`
}

const allChanged = computed(() => w.flows.filter((f) => f.status !== 'UNCHANGED').map((f) => f.name))
function selectChanged() {
  w.selected = [...allChanged.value]
}
function selectAll() {
  w.selected = w.flows.map((f) => f.name)
}
</script>

<template>
  <div v-if="w.loading.flows" class="muted row"><Icon name="loader" /> Hashing flow files and comparing with the Registry…</div>

  <div v-else class="stack">
    <div class="row">
      <span class="pill pill-info">{{ counts.NEW }} new</span>
      <span class="pill pill-warn">{{ counts.CHANGED }} changed</span>
      <span class="pill pill-neutral">{{ counts.UNCHANGED }} unchanged</span>
      <span class="spacer" />
      <button class="btn btn-sm" type="button" @click="selectChanged">Select changed</button>
      <button class="btn btn-sm" type="button" @click="selectAll">Select all</button>
      <button class="btn btn-sm" type="button" title="Rescan flow files" @click="w.loadFlows()">
        <Icon name="refresh" :size="14" /> Rescan
      </button>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col" class="cb"><span class="sr-only">Deploy</span></th>
            <th scope="col">Flow</th>
            <th scope="col">Status</th>
            <th scope="col">Local hash</th>
            <th scope="col">Registry hash</th>
            <th scope="col">Version</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in w.flows" :key="f.name">
            <td class="cb">
              <input
                :id="`cb-${f.name}`"
                type="checkbox"
                :checked="w.selected.includes(f.name)"
                @change="w.toggleFlow(f.name)"
              />
            </td>
            <td>
              <label :for="`cb-${f.name}`" class="flow-name">{{ f.name }}</label>
              <div class="small muted">{{ f.file }}</div>
            </td>
            <td><span class="pill" :class="BADGE[f.status]">{{ LABEL[f.status] }}</span></td>
            <td class="mono small" :title="f.localHash">{{ f.localHash.slice(0, 12) }}</td>
            <td class="mono small muted" :title="f.registryHash ?? ''">{{ f.registryHash?.slice(0, 12) ?? '—' }}</td>
            <td class="mono small">{{ versionText(f, w.selected.includes(f.name)) }}</td>
          </tr>
          <tr v-if="!w.flows.length">
            <td colspan="6" class="muted empty">No flow files found in the configured flows directory.</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="small muted">Unchanged flows are skipped by default. Tick one to force a redeploy.</div>
  </div>
</template>

<style scoped>
.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
table {
  width: 100%;
  border-collapse: collapse;
  min-width: 720px;
}
th {
  background: var(--surface-sunken);
  text-align: left;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  padding: 12px 8px;
}
td {
  padding: 14px 8px;
  border-top: 1px solid var(--border);
  vertical-align: middle;
}
th:first-child,
td:first-child {
  padding-left: 16px;
}
th:last-child,
td:last-child {
  padding-right: 16px;
}
.cb {
  width: 44px;
}
.cb input {
  width: 18px;
  height: 18px;
  accent-color: var(--accent);
  cursor: pointer;
}
.flow-name {
  font-weight: 500;
  cursor: pointer;
}
.empty {
  text-align: center;
  padding: 28px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
