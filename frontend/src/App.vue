<script setup lang="ts">
import { onMounted } from 'vue'
import { useWizard, STEP_DEFS } from './stores/wizard'
import AppHeader from './components/AppHeader.vue'
import StepNav from './components/StepNav.vue'
import WizardFooter from './components/WizardFooter.vue'
import Icon from './components/ui/Icon.vue'
import TargetStep from './steps/TargetStep.vue'
import RegistryStep from './steps/RegistryStep.vue'
import FlowsStep from './steps/FlowsStep.vue'
import ParametersStep from './steps/ParametersStep.vue'
import ReviewStep from './steps/ReviewStep.vue'
import DeployStep from './steps/DeployStep.vue'

const w = useWizard()
const stepComponents = [TargetStep, RegistryStep, FlowsStep, ParametersStep, ReviewStep, DeployStep]

onMounted(() => w.loadEnvironments())
</script>

<template>
  <div class="app">
    <AppHeader />
    <div class="layout">
      <StepNav />
      <main class="main">
        <div class="head">
          <div class="muted small eyebrow">STEP {{ w.step + 1 }} OF {{ STEP_DEFS.length }}</div>
          <h1>{{ STEP_DEFS[w.step].title }}</h1>
          <p class="muted">{{ STEP_DEFS[w.step].desc }}</p>
        </div>

        <div v-if="w.error" class="alert alert-err err" role="alert">
          <Icon name="error" />
          <div class="spacer">{{ w.error }}</div>
          <button class="btn btn-sm" type="button" @click="w.error = null">Dismiss</button>
        </div>

        <div class="body">
          <component :is="stepComponents[w.step]" />
        </div>
        <WizardFooter />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.layout {
  flex: 1;
  width: 100%;
  max-width: 1320px;
  margin: 0 auto;
  padding: 28px;
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  align-items: flex-start;
}
.main {
  flex: 999 1 600px;
  min-width: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
}
.head {
  padding: 28px 32px 8px;
}
.eyebrow {
  letter-spacing: 0.5px;
}
h1 {
  margin: 6px 0 4px;
  font-size: 24px;
  font-weight: 600;
}
.head p {
  max-width: 660px;
  line-height: 1.5;
}
.err {
  margin: 16px 32px 0;
  align-items: center;
}
.body {
  padding: 20px 32px 28px;
  flex: 1;
}
@media (max-width: 640px) {
  .layout {
    padding: 16px;
    gap: 16px;
  }
  .head,
  .body {
    padding-left: 18px;
    padding-right: 18px;
  }
  .err {
    margin: 16px 18px 0;
  }
}
</style>
