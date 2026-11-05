<script setup lang="ts">
import { useWizard } from '../stores/wizard'
import Icon from '../components/ui/Icon.vue'

const w = useWizard()

function onInput(flow: string, name: string, original: string | null, e: Event) {
  w.setParam(flow, name, (e.target as HTMLInputElement).value, original)
}
function isChanged(flow: string, name: string) {
  return w.overrides[flow] !== undefined && name in w.overrides[flow]
}
</script>

<template>
  <div v-if="w.loading.params" class="muted row"><Icon name="loader" /> Reading parameter contexts…</div>

  <div v-else class="stack">
    <section v-for="flow in w.selected" :key="flow" class="panel">
      <div class="row" style="justify-content: space-between; margin-bottom: 14px">
        <h2 class="flow">{{ flow }}</h2>
        <span class="mono small muted">context: {{ w.paramContexts[flow]?.name ?? 'none' }}</span>
      </div>

      <div v-if="!w.paramContexts[flow]?.parameters.length" class="muted small">
        This flow has no parameter context.
      </div>

      <div v-else class="grid-auto" style="--min: 240px; gap: 12px">
        <div v-for="p in w.paramContexts[flow]!.parameters" :key="p.name" class="field">
          <label :for="`p-${flow}-${p.name}`" class="mono pname">
            {{ p.name }}
            <span v-if="p.sensitive" class="tag-secret"><Icon name="lock" :size="12" /> from Secret</span>
            <span v-else-if="isChanged(flow, p.name)" class="tag-changed">changed</span>
          </label>
          <input
            :id="`p-${flow}-${p.name}`"
            class="input"
            :type="p.sensitive ? 'password' : 'text'"
            :value="p.sensitive ? '••••••••' : w.paramValue(flow, p.name, p.value)"
            :disabled="p.sensitive"
            :title="p.description"
            @input="onInput(flow, p.name, p.value, $event)"
          />
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.flow {
  font-size: 15px;
  font-weight: 600;
}
.pname {
  font-size: 12px !important;
  color: var(--text-2);
  font-weight: 400 !important;
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.tag-secret {
  color: var(--warn-icon);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--font);
}
.tag-changed {
  color: var(--info-fg);
  font-family: var(--font);
}
</style>
