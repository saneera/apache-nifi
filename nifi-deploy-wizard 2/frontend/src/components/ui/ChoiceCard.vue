<script setup lang="ts">
defineProps<{ selected: boolean; disabled?: boolean }>()
defineEmits<{ select: [] }>()
</script>

<template>
  <button
    type="button"
    class="choice"
    :class="{ 'is-selected': selected }"
    :aria-pressed="selected"
    :disabled="disabled"
    @click="$emit('select')"
  >
    <slot />
  </button>
</template>

<style scoped>
.choice {
  display: block;
  width: 100%;
  text-align: left;
  font-family: var(--font);
  color: var(--text);
  background: var(--surface-sunken);
  border: 2px solid var(--border);
  border-radius: var(--radius);
  padding: 16px 18px;
  cursor: pointer;
}
.choice:hover:not(:disabled) {
  border-color: var(--border-hover);
}
.choice.is-selected {
  background: var(--surface-selected);
  border-color: var(--accent);
}
.choice:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
