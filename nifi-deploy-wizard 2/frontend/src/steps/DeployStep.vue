<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { STAGES, STAGE_LABELS, type Stage, type StageStatus } from '../api'
import { useWizard } from '../stores/wizard'
import Icon from '../components/ui/Icon.vue'

const w = useWizard()
const logEl = ref<HTMLPreElement | null>(null)
const follow = ref(true)

const PILL: Record<StageStatus, string> = {
  pending: 'pill-neutral',
  started: 'pill-info',
  done: 'pill-ok',
  failed: 'pill-err',
  skipped: 'pill-neutral',
}

const rows = computed(() =>
  Object.entries(w.progress).map(([flow, stages]) => {
    const list = STAGES.map((s: Stage) => ({ stage: s, label: STAGE_LABELS[s], status: stages[s] }))
    const failed = list.find((s) => s.status === 'failed')
    const active = list.find((s) => s.status === 'started')
    const allDone = list.every((s) => s.status === 'done')
    const skipped = list.some((s) => s.status === 'skipped') && !list.some((s) => s.status === 'pending')
    const [text, cls] = failed
      ? [`Failed at ${failed.label}`, 'pill-err']
      : allDone
        ? ['Done', 'pill-ok']
        : skipped
          ? ['Skipped · no changes', 'pill-neutral']
          : active
            ? [`${active.label}…`, 'pill-info']
            : w.finished
              ? ['Not run', 'pill-neutral']
              : ['Queued', 'pill-neutral']
    return { flow, list, text, cls, detail: w.stageDetail[flow] }
  }),
)

const phaseClass = computed(() =>
  w.phase === 'Succeeded' ? 'pill-ok' : w.phase === 'Failed' ? 'pill-err' : w.phase === 'Cancelled' ? 'pill-warn' : 'pill-info',
)

const meta = computed(() => w.deploymentMeta)
const startedAt = computed(() => (meta.value ? new Date(meta.value.startedAt).toLocaleString() : ''))

watch(
  () => w.logs.length,
  async () => {
    if (!follow.value) return
    await nextTick()
    if (logEl.value) logEl.value.scrollTop = logEl.value.scrollHeight
  },
)
function onScroll() {
  const el = logEl.value
  if (el) follow.value = el.scrollHeight - el.scrollTop - el.clientHeight < 24
}
</script>

<template>
  <div class="stack" style="--gap: 18px">
    <div>
      <div class="row" style="justify-content: space-between; margin-bottom: 8px; font-size: 13px">
        <span class="row" style="gap: 8px">
          <span class="pill" :class="phaseClass">{{ w.phase ?? 'Starting' }}</span>
          <span class="mono small muted">{{ w.deploymentId }}</span>
          <span v-if="meta?.startedBy" class="small muted">· {{ meta.startedBy }} · {{ startedAt }}</span>
        </span>
        <span class="row" style="gap: 12px">
          <span class="mono muted">{{ w.percent }}%</span>
          <button
            v-if="!w.finished"
            class="btn btn-sm"
            type="button"
            :disabled="w.cancelling"
            title="Stops after the flow currently being deployed"
            @click="w.cancelDeployment()"
          >
            {{ w.cancelling ? 'Cancelling after current flow…' : 'Cancel' }}
          </button>
        </span>
      </div>
      <div
        class="bar"
        role="progressbar"
        :aria-valuenow="w.percent"
        aria-valuemin="0"
        aria-valuemax="100"
        aria-label="Deployment progress"
      >
        <div
          class="fill"
          :class="{ ok: w.phase === 'Succeeded', bad: w.phase === 'Failed', warn: w.phase === 'Cancelled' }"
          :style="{ width: w.percent + '%' }"
        />
      </div>
    </div>

    <div
      v-if="w.summary && w.finished"
      class="alert summary"
      :class="w.phase === 'Succeeded' ? 'alert-ok' : w.phase === 'Cancelled' ? 'alert-warn' : 'alert-err'"
      role="status"
    >
      <div>
        <div class="small sum-l">Result</div>
        <div class="sum-v">
          {{ w.phase === 'Succeeded' ? 'Deployment complete' : w.phase === 'Cancelled' ? 'Cancelled' : 'Deployment failed' }}
        </div>
      </div>
      <div><div class="small sum-l">Total</div><div class="sum-v">{{ w.summary.total }}</div></div>
      <div><div class="small sum-l">Deployed</div><div class="sum-v">{{ w.summary.deployed }}</div></div>
      <div><div class="small sum-l">Skipped</div><div class="sum-v">{{ w.summary.skipped }}</div></div>
      <div><div class="small sum-l">Failed</div><div class="sum-v">{{ w.summary.failed }}</div></div>
    </div>

    <div class="stack" style="--gap: 10px">
      <div v-for="r in rows" :key="r.flow" class="panel flow">
        <div class="row" style="justify-content: space-between">
          <span style="font-weight: 600">{{ r.flow }}</span>
          <span class="pill" :class="r.cls">{{ r.text }}</span>
        </div>
        <div class="row stages">
          <span v-for="s in r.list" :key="s.stage" class="pill" :class="PILL[s.status]">
            <Icon v-if="s.status === 'started'" name="loader" :size="12" />
            <span v-else class="dot" />
            {{ s.label }}
          </span>
        </div>
        <div v-if="r.detail" class="mono small muted">{{ r.detail }}</div>
      </div>
    </div>

    <div class="console">
      <div class="console-head small muted">
        <span>Deployment log</span>
        <span>{{ w.logs.length }} lines<template v-if="!follow"> · scroll paused</template></span>
      </div>
      <pre ref="logEl" tabindex="0" aria-label="Deployment log output" @scroll="onScroll">{{ w.logs.join('\n') }}</pre>
    </div>
  </div>
</template>

<style scoped>
.bar {
  height: 8px;
  background: var(--neutral-bg);
  border-radius: 999px;
  overflow: hidden;
}
.fill {
  height: 100%;
  background: var(--accent);
  border-radius: 999px;
  transition: width 0.3s;
}
.fill.ok {
  background: var(--ok-dot);
}
.fill.bad {
  background: #ff6b6b;
}
.fill.warn {
  background: var(--warn-icon);
}
.summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 12px;
}
.sum-l {
  opacity: 0.8;
}
.sum-v {
  font-weight: 600;
  margin-top: 4px;
  color: var(--text);
}
.flow {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.stages {
  gap: 6px;
}
.console {
  background: var(--surface-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}
.console-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}
pre {
  margin: 0;
  padding: 12px 14px;
  height: 260px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-2);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
