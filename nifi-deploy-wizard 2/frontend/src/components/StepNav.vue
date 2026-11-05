<script setup lang="ts">
import { computed } from 'vue'
import { STEP_DEFS, useWizard } from '../stores/wizard'
import Icon from './ui/Icon.vue'

const w = useWizard()

const items = computed(() =>
  STEP_DEFS.map((d, i) => {
    const deployDone = i === 5 && w.phase === 'Succeeded'
    const done = i < w.step || deployDone
    const active = i === w.step && !deployDone
    const locked = (w.step === 5 && !w.finished) || i > w.maxStep || i === 5
    return { ...d, i, done, active, locked }
  }),
)
</script>

<template>
  <nav class="nav" aria-label="Deployment steps">
    <div class="muted small caps">New deployment</div>
    <ol>
      <li v-for="it in items" :key="it.i">
        <button
          type="button"
          class="step"
          :class="{ active: it.active, done: it.done }"
          :disabled="it.locked"
          :aria-current="it.active ? 'step' : undefined"
          @click="w.goTo(it.i)"
        >
          <span class="circle">
            <Icon v-if="it.done" name="check" :size="14" />
            <template v-else>{{ it.i + 1 }}</template>
          </span>
          <span class="text">
            <span class="label">{{ it.label }}</span>
            <span class="muted small">{{ it.sub }}</span>
          </span>
        </button>
      </li>
    </ol>
    <dl class="summary small">
      <div><dt class="muted">Target</dt><dd>{{ w.env?.name ?? '—' }}</dd></div>
      <div><dt class="muted">Bucket</dt><dd class="mono">{{ w.deploymentMeta?.bucketName ?? w.bucket?.name ?? '—' }}</dd></div>
      <div><dt class="muted">Flows selected</dt><dd>{{ w.deploymentMeta?.flows.length ?? w.selected.length }}</dd></div>
    </dl>
  </nav>
</template>

<style scoped>
.nav {
  flex: 1 1 250px;
  max-width: 100%;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
}
.caps {
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 16px;
}
ol {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.step {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  padding: 10px;
  border-radius: 8px;
  background: none;
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.step:hover:not(:disabled):not(.active) {
  background: #151d27;
}
.step:disabled {
  cursor: default;
}
.step.active {
  background: #16263a;
}
.circle {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--border-strong);
  color: var(--muted);
}
.done .circle {
  background: var(--ok-border);
  border-color: var(--ok-border);
  color: var(--ok-fg);
}
.active .circle {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--on-accent);
}
.text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.label {
  font-weight: 500;
  color: var(--text-2);
}
.active .label {
  font-weight: 600;
  color: var(--text);
}
.done .label {
  color: var(--text);
}
.summary {
  margin: 20px 0 0;
  padding-top: 16px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.summary div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
dd {
  margin: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
</style>
