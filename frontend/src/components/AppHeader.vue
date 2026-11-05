<script setup lang="ts">
import { isMock } from '../api'
import { useWizard } from '../stores/wizard'
import Icon from './ui/Icon.vue'

const w = useWizard()
</script>

<template>
  <header class="header">
    <div class="brand">
      <div class="logo"><Icon name="logo" :size="20" /></div>
      <div>
        <div class="title">Flow Deployer</div>
        <div class="muted small">NiFi · Registry · Kubernetes Job</div>
      </div>
    </div>
    <div class="row">
      <span v-if="isMock" class="pill pill-warn">MOCK MODE</span>
      <span v-if="w.env" class="env-pill" :style="{ color: w.env.color, borderColor: w.env.color }">
        {{ w.env.name.toUpperCase() }}
      </span>
      <span v-if="w.env" class="cluster mono small muted">{{ w.env.cluster }}</span>
    </div>
  </header>
</template>

<style scoped>
.header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 28px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-header);
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.logo {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: #16263a;
  color: var(--accent);
  display: flex;
  align-items: center;
  justify-content: center;
}
.title {
  font-weight: 700;
  font-size: 16px;
}
.env-pill {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 1px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid;
}
.cluster {
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 6px 12px;
}
</style>
