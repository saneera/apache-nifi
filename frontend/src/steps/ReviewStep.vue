<script setup lang="ts">
import { useWizard } from '../stores/wizard'

const w = useWizard()
</script>

<template>
  <div class="stack" style="--gap: 20px">
    <div class="grid-auto" style="--min: 160px; gap: 12px">
      <div class="panel stat">
        <div class="small muted">To deploy</div>
        <div class="big">{{ w.selected.length }}</div>
      </div>
      <div class="panel stat">
        <div class="small muted">Skipped</div>
        <div class="big">{{ w.flows.length - w.selected.length }}</div>
      </div>
      <div class="panel stat">
        <div class="small muted">Target</div>
        <div class="mid">{{ w.env?.name }}</div>
      </div>
      <div class="panel stat">
        <div class="small muted">Bucket</div>
        <div class="mid mono">{{ w.bucket?.name }}</div>
      </div>
    </div>

    <div class="grid-auto" style="--min: 300px">
      <div class="panel stack">
        <h2 class="h">Options</h2>
        <label class="toggle">
          <span>
            <span style="display: block; font-weight: 500">Start flows after deploy</span>
            <span class="small muted">START_FLOW</span>
          </span>
          <input v-model="w.startFlow" type="checkbox" />
        </label>
        <div class="field">
          <label for="pos">Position step (px)</label>
          <input id="pos" v-model.number="w.positionStep" class="input" type="number" min="0" step="50" />
        </div>
        <div class="field">
          <label for="cmt">Version comment</label>
          <input id="cmt" v-model="w.comment" class="input" placeholder="e.g. Release 2026.11 – CR-1234" />
        </div>
      </div>

      <div class="panel job stack" style="--gap: 10px">
        <div class="row" style="justify-content: space-between">
          <h2 class="h">Kubernetes Job environment</h2>
        </div>
        <dl class="env">
          <template v-for="[k, v] in w.jobEnvPreview" :key="k">
            <dt>{{ k }}</dt>
            <dd>{{ v || '—' }}</dd>
          </template>
        </dl>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stat {
  padding: 16px;
}
.big {
  font-size: 28px;
  font-weight: 600;
  margin-top: 4px;
}
.mid {
  font-size: 16px;
  font-weight: 600;
  margin-top: 10px;
  overflow-wrap: anywhere;
}
.h {
  font-size: 14px;
  font-weight: 600;
}
.toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  min-height: 44px;
}
.toggle input {
  width: 20px;
  height: 20px;
  accent-color: var(--accent);
}
.job {
  background: var(--bg);
}
.env {
  margin: 0;
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 4px 14px;
  font-family: var(--mono);
  font-size: 12px;
  line-height: 1.6;
}
dt {
  color: var(--muted);
}
dd {
  margin: 0;
  color: var(--text-2);
  overflow-wrap: anywhere;
}
@media (max-width: 520px) {
  .env {
    grid-template-columns: 1fr;
  }
  dd {
    margin-bottom: 6px;
  }
}
</style>
