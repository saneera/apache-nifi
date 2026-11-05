<script setup lang="ts">
import { computed } from 'vue'
import { useWizard } from '../stores/wizard'
import Icon from './ui/Icon.vue'

const w = useWizard()
const nextLabel = computed(() => (w.step === 4 ? 'Start deployment' : w.step === 5 ? 'New deployment' : 'Next'))
const backDisabled = computed(() => w.step === 0 || w.step === 5)
</script>

<template>
  <footer class="footer">
    <button class="btn" type="button" :disabled="backDisabled" @click="w.back()">
      <Icon name="chevronLeft" :size="16" /> Back
    </button>
    <span class="muted small hint" aria-live="polite">{{ w.footerHint }}</span>
    <button class="btn btn-primary" type="button" :disabled="!w.canNext" @click="w.next()">
      <Icon v-if="w.loading.deploy" name="loader" :size="16" />
      {{ nextLabel }}
      <Icon v-if="w.step < 5" name="chevronRight" :size="16" />
    </button>
  </footer>
</template>

<style scoped>
.footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px 32px;
  border-top: 1px solid var(--border);
}
.hint {
  text-align: center;
  flex: 1;
}
@media (max-width: 640px) {
  .footer {
    padding: 16px 18px;
  }
  .hint {
    order: -1;
    flex-basis: 100%;
  }
}
</style>
