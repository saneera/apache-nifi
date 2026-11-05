<script setup lang="ts">
import { useWizard } from '../stores/wizard'
import ChoiceCard from '../components/ui/ChoiceCard.vue'
import StatusDot from '../components/ui/StatusDot.vue'
import Icon from '../components/ui/Icon.vue'
import RecentDeployments from '../components/RecentDeployments.vue'

const w = useWizard()

const PLACEHOLDER_CHECKS = ['NiFi API reachable', 'Authenticated', 'Registry reachable', 'Root process group']
</script>

<template>
  <div class="stack" style="--gap: 24px">
    <div v-if="w.loading.envs" class="muted row"><Icon name="loader" /> Loading environments…</div>

    <div class="grid-auto" style="--min: 240px">
      <ChoiceCard v-for="e in w.environments" :key="e.id" :selected="e.id === w.envId" @select="w.selectEnv(e.id)">
        <div class="row" style="justify-content: space-between">
          <span class="env-name">{{ e.name }}</span>
          <span class="env-dot" :style="{ background: e.color }" />
        </div>
        <div class="muted" style="font-size: 13px; margin-top: 6px">{{ e.description }}</div>
        <div class="mono small" style="color: var(--text-2); margin-top: 12px">{{ e.cluster }}</div>
      </ChoiceCard>
    </div>

    <div v-if="w.env" class="grid-auto">
      <div class="field">
        <label for="nifiUrl">NiFi URL</label>
        <input id="nifiUrl" class="input" :value="w.env.nifiUrl" readonly />
      </div>
      <div class="field">
        <label for="regUrl">Registry URL</label>
        <input id="regUrl" class="input" :value="w.env.registryUrl" readonly />
      </div>
      <div class="field">
        <label for="rpgUrl">Target RPG URL</label>
        <input id="rpgUrl" class="input" :value="w.env.rpgUrl" readonly />
      </div>
      <div class="field">
        <label for="cred">Credentials</label>
        <input id="cred" class="input" :value="`${w.env.credentialsRef} (server-side)`" readonly />
      </div>
    </div>

    <div class="panel stack" style="--gap: 14px">
      <div class="row" style="justify-content: space-between">
        <div>
          <div style="font-weight: 600">Connection check</div>
          <div class="muted" style="font-size: 13px">Same calls as wait_for_nifi and authenticate in the script</div>
        </div>
        <button class="btn" type="button" :disabled="!w.envId || w.loading.check" @click="w.runChecks()">
          <Icon :name="w.loading.check ? 'loader' : 'refresh'" :size="16" />
          {{ w.loading.check ? 'Checking…' : w.checks.length ? 'Run again' : 'Test connection' }}
        </button>
      </div>
      <ul class="checks">
        <template v-if="w.checks.length">
          <li v-for="c in w.checks" :key="c.key">
            <StatusDot :state="c.ok ? 'ok' : 'fail'" />
            <span class="spacer">{{ c.label }}</span>
            <span class="mono small" :class="c.ok ? 'muted' : 'fail-text'">{{ c.detail }}</span>
          </li>
        </template>
        <template v-else>
          <li v-for="label in PLACEHOLDER_CHECKS" :key="label">
            <StatusDot :state="w.loading.check ? 'running' : 'idle'" />
            <span class="spacer">{{ label }}</span>
            <span class="mono small muted">{{ w.loading.check ? 'checking…' : 'not run' }}</span>
          </li>
        </template>
      </ul>
    </div>

    <RecentDeployments />
  </div>
</template>

<style scoped>
.env-name {
  font-weight: 600;
  font-size: 16px;
}
.env-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.checks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13px;
}
.checks li {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.fail-text {
  color: var(--err-fg);
}
</style>
