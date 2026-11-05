<script setup lang="ts">
import type { DeploymentRecord } from '../api'
import { useWizard } from '../stores/wizard'

const w = useWizard()

const PILL: Record<DeploymentRecord['phase'], string> = {
  Running: 'pill-info',
  Succeeded: 'pill-ok',
  Failed: 'pill-err',
  Cancelled: 'pill-warn',
}

function when(iso: string) {
  const d = new Date(iso)
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' }) + ' ' +
    d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <section class="panel stack" style="--gap: 10px" aria-labelledby="recent-h">
    <div class="row" style="justify-content: space-between">
      <h2 id="recent-h" class="h">Recent deployments</h2>
      <button class="btn btn-sm" type="button" @click="w.loadRecent()">Refresh</button>
    </div>
    <p v-if="!w.recent.length" class="small muted">No deployments yet.</p>
    <ul v-else class="list">
      <li v-for="r in w.recent.slice(0, 8)" :key="r.id">
        <button type="button" class="item" @click="w.attach(r)">
          <span class="pill" :class="PILL[r.phase]">{{ r.phase }}</span>
          <span class="what">
            <span class="title">{{ r.environmentName }} · {{ r.flows.length }} flow{{ r.flows.length > 1 ? 's' : '' }}</span>
            <span class="small muted">{{ r.flows.join(', ') }}</span>
          </span>
          <span class="who small muted">
            <span>{{ r.startedBy }}</span>
            <span>{{ when(r.startedAt) }}</span>
          </span>
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.h {
  font-size: 14px;
  font-weight: 600;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  min-height: 48px;
  padding: 8px 12px;
  background: none;
  border: 1px solid var(--border);
  border-radius: 8px;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.item:hover {
  border-color: var(--border-hover);
  background: #151d27;
}
.what {
  flex: 1;
  min-width: 160px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow: hidden;
}
.what .small {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.title {
  font-weight: 500;
}
.who {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
</style>
