import { defineStore } from 'pinia'
import { computed, reactive, ref, watch } from 'vue'
import {
  api,
  STAGES,
  type Bucket,
  type CheckResult,
  type EnvironmentInfo,
  type DeployPhase,
  type DeploymentRecord,
  type FlowStatus,
  type ParameterContext,
  type RegistryClient,
  type Stage,
  type StageStatus,
  type SummaryEvent,
} from '../api'

export const STEP_DEFS = [
  {
    label: 'Target',
    sub: 'Cluster & connection',
    title: 'Choose target environment',
    desc: 'Pick the NiFi instance to deploy to. URLs come from the environment profile; credentials stay on the server.',
  },
  {
    label: 'Registry & bucket',
    sub: 'Find or create bucket',
    title: 'Registry client and bucket',
    desc: 'Flows are versioned in NiFi Registry. NiFi pulls versions through its Registry Client. A missing bucket (or Registry Client) can be created here.',
  },
  {
    label: 'Flows',
    sub: 'Select what to deploy',
    title: 'Select flows',
    desc: 'Each flow file is hashed and compared with the hash stored on the Registry flow, so only real changes are deployed.',
  },
  {
    label: 'Parameters',
    sub: 'Context values',
    title: 'Parameter contexts',
    desc: 'Review values injected into each flow. Sensitive values are read from Secrets by the job and never shown here.',
  },
  {
    label: 'Review',
    sub: 'Plan & options',
    title: 'Review deployment plan',
    desc: 'Check what will happen to each flow. Nothing changes in NiFi or the Registry until you start.',
  },
  {
    label: 'Deploy',
    sub: 'Live progress',
    title: 'Deployment',
    desc: 'The server deploys each flow through the NiFi and Registry APIs. It keeps running if you close this tab.',
  },
] as const

type Loadable = 'envs' | 'check' | 'registry' | 'registryClient' | 'bucket' | 'flows' | 'params' | 'deploy'

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export const useWizard = defineStore('wizard', () => {
  // ---------- navigation ----------
  const step = ref(0)
  const maxStep = ref(0)
  const error = ref<string | null>(null)
  const loading = reactive<Record<Loadable, boolean>>({
    envs: false,
    check: false,
    registry: false,
    registryClient: false,
    bucket: false,
    flows: false,
    params: false,
    deploy: false,
  })

  async function guarded<T>(key: Loadable, fn: () => Promise<T>): Promise<T | undefined> {
    loading[key] = true
    error.value = null
    try {
      return await fn()
    } catch (e) {
      error.value = errMsg(e)
      return undefined
    } finally {
      loading[key] = false
    }
  }

  // ---------- step 1: target ----------
  const environments = ref<EnvironmentInfo[]>([])
  const envId = ref('')
  const env = computed(() => environments.value.find((e) => e.id === envId.value))
  const checks = ref<CheckResult[]>([])
  const connectionOk = computed(() => checks.value.length > 0 && checks.value.every((c) => c.ok))

  async function loadEnvironments() {
    const list = await guarded('envs', () => api.environments())
    if (list) {
      environments.value = list
      if (!envId.value && list.length) selectEnv(list[0].id)
    }
  }

  function selectEnv(id: string) {
    if (id === envId.value) return
    envId.value = id
    checks.value = []
    resetFrom(1)
    newBucketName.value = env.value?.defaultBucket ?? ''
  }

  async function runChecks() {
    checks.value = []
    const res = await guarded('check', () => api.check(envId.value))
    if (res) checks.value = res
  }

  // ---------- step 2: registry & bucket ----------
  const registryClients = ref<RegistryClient[]>([])
  const registryClientId = ref('')
  const buckets = ref<Bucket[]>([])
  const bucketMode = ref<'create' | 'existing'>('create')
  const expectedBucketFound = ref<boolean | null>(null)
  const newBucketName = ref('')
  const newBucketDesc = ref('Flows deployed by Flow Deployer')
  const createdBucket = ref<Bucket | null>(null)
  const selectedBucketId = ref('')

  const bucket = computed<Bucket | null>(() =>
    bucketMode.value === 'create'
      ? createdBucket.value
      : (buckets.value.find((b) => b.id === selectedBucketId.value) ?? null),
  )

  // A different bucket means a different Registry comparison: re-scan flows when it changes.
  watch(
    () => bucket.value?.id,
    (id, prev) => {
      if (id !== prev) resetFrom(2)
    },
  )

  async function loadRegistry() {
    await guarded('registry', async () => {
      const [clients, list, resolved] = await Promise.all([
        api.registryClients(envId.value),
        api.buckets(envId.value),
        api.resolveBucket(envId.value, newBucketName.value),
      ])
      registryClients.value = clients
      if (!clients.some((c) => c.id === registryClientId.value)) registryClientId.value = clients[0]?.id ?? ''
      buckets.value = list
      expectedBucketFound.value = resolved.found
      if (resolved.found && resolved.bucket) {
        // Expected bucket already exists: preselect it.
        bucketMode.value = 'existing'
        selectedBucketId.value = resolved.bucket.id
      } else {
        bucketMode.value = 'create'
        selectedBucketId.value = list[0]?.id ?? ''
      }
    })
  }

  async function createRegistryClient() {
    const c = await guarded('registryClient', () => api.createRegistryClient(envId.value))
    if (c) {
      registryClients.value = [c, ...registryClients.value]
      registryClientId.value = c.id
    }
  }

  async function createBucket() {
    const res = await guarded('bucket', () =>
      api.createBucket(envId.value, { name: newBucketName.value.trim(), description: newBucketDesc.value }),
    )
    if (res) {
      createdBucket.value = res.bucket
      if (!buckets.value.some((b) => b.id === res.bucket.id)) buckets.value.push(res.bucket)
      resetFrom(2)
    }
  }

  // ---------- step 3: flows ----------
  const flows = ref<FlowStatus[]>([])
  const selected = ref<string[]>([])
  const selectedFlows = computed(() => flows.value.filter((f) => selected.value.includes(f.name)))

  async function loadFlows() {
    const res = await guarded('flows', () => api.flows(envId.value, bucket.value?.id ?? null))
    if (res) {
      flows.value = res
      // Default: deploy everything that is new or changed.
      selected.value = res.filter((f) => f.status !== 'UNCHANGED').map((f) => f.name)
    }
  }

  function toggleFlow(name: string) {
    selected.value = selected.value.includes(name)
      ? selected.value.filter((n) => n !== name)
      : [...selected.value, name]
  }

  // ---------- step 4: parameters ----------
  const paramContexts = ref<Record<string, ParameterContext | null>>({})
  const overrides = ref<Record<string, Record<string, string>>>({})

  async function loadParameters() {
    const missing = selected.value.filter((n) => !(n in paramContexts.value))
    if (!missing.length) return
    await guarded('params', async () => {
      const results = await Promise.all(missing.map((n) => api.parameters(n)))
      missing.forEach((n, i) => (paramContexts.value[n] = results[i]))
    })
  }

  function paramValue(flow: string, name: string, fallback: string | null): string {
    return overrides.value[flow]?.[name] ?? fallback ?? ''
  }

  function setParam(flow: string, name: string, value: string, original: string | null) {
    const forFlow = { ...(overrides.value[flow] ?? {}) }
    if (value === (original ?? '')) delete forFlow[name]
    else forFlow[name] = value
    overrides.value = { ...overrides.value, [flow]: forFlow }
  }

  // ---------- step 5: options ----------
  const startFlow = ref(false)
  const positionStep = ref(400)
  const comment = ref('')

  /** What the backend will do for each selected flow — shown on the Review step. */
  const plan = computed(() =>
    selectedFlows.value.map((f) => {
      const v = f.registryVersion
      const changedParams = Object.keys(overrides.value[f.name] ?? {}).length
      return {
        name: f.name,
        status: f.status,
        registry: v == null ? 'Create Registry flow, upload v1' : `Upload v${v + 1} (now v${v})`,
        nifi: v == null ? 'Import new process group' : 'Change process group version (or import if missing)',
        params: changedParams ? `${changedParams} override${changedParams > 1 ? 's' : ''}` : 'From flow file',
        forced: f.status === 'UNCHANGED',
      }
    }),
  )

  // ---------- step 6: deploy ----------
  const ACTIVE_KEY = 'flow-deployer.activeDeployment'
  const deploymentId = ref('')
  const deploymentMeta = ref<DeploymentRecord | null>(null)
  const phase = ref<DeployPhase | null>(null)
  const logs = ref<string[]>([])
  const progress = ref<Record<string, Record<Stage, StageStatus>>>({})
  const stageDetail = ref<Record<string, string>>({})
  const summary = ref<SummaryEvent | null>(null)
  const cancelling = ref(false)
  const recent = ref<DeploymentRecord[]>([])
  let unsubscribe: (() => void) | null = null

  const finished = computed(() => phase.value != null && phase.value !== 'Running')

  const percent = computed(() => {
    const flowsList = Object.values(progress.value)
    const total = flowsList.length * STAGES.length
    if (!total) return 0
    if (phase.value === 'Succeeded') return 100
    const doneCount = flowsList.reduce(
      (acc, st) => acc + STAGES.filter((s) => st[s] === 'done' || st[s] === 'skipped' || st[s] === 'failed').length,
      0,
    )
    return Math.round((doneCount / total) * 100)
  })

  function remember(id: string | null) {
    try {
      if (id) localStorage.setItem(ACTIVE_KEY, id)
      else localStorage.removeItem(ACTIVE_KEY)
    } catch {
      /* storage may be blocked — reattach just won't happen after a reload */
    }
  }

  /** Shows a deployment (new, still running after a reload, or from history) and streams its events. */
  function attach(record: DeploymentRecord) {
    unsubscribe?.()
    deploymentId.value = record.id
    deploymentMeta.value = record
    if (environments.value.some((e) => e.id === record.environmentId)) envId.value = record.environmentId
    logs.value = []
    summary.value = record.summary
    phase.value = record.phase
    cancelling.value = false
    stageDetail.value = {}
    progress.value = Object.fromEntries(
      record.flows.map((f) => [f, Object.fromEntries(STAGES.map((s) => [s, 'pending'])) as Record<Stage, StageStatus>]),
    )
    step.value = 5
    maxStep.value = 5
    if (record.phase === 'Running') remember(record.id)

    unsubscribe = api.subscribe(record.id, {
      onLog: (line) => {
        logs.value.push(line)
        if (logs.value.length > 5000) logs.value.splice(0, logs.value.length - 5000)
      },
      onProgress: (p) => {
        const forFlow = progress.value[p.flow]
        if (!forFlow) return
        forFlow[p.stage] = p.status
        if (p.status === 'skipped') STAGES.forEach((s) => forFlow[s] === 'pending' && (forFlow[s] = 'skipped'))
        if (p.detail) stageDetail.value[p.flow] = p.detail
      },
      onPhase: (ph) => {
        phase.value = ph
        if (ph !== 'Running') {
          remember(null)
          loadRecent()
        }
      },
      onSummary: (s) => (summary.value = s),
      onError: (m) => {
        // History entries loaded from the audit file have no event stream — show their summary only.
        if (record.phase === 'Running') error.value = m
        else logs.value.push(`[deployer] ${m}. Showing the recorded summary only.`)
      },
    })
  }

  async function startDeployment() {
    const e = env.value
    const b = bucket.value
    if (!e || !b) return false
    const started = await guarded('deploy', () =>
      api.deploy({
        environmentId: e.id,
        registryClientId: registryClientId.value,
        bucketId: b.id,
        bucketName: b.name,
        flows: [...selected.value],
        parameterOverrides: overrides.value,
        startFlow: startFlow.value,
        positionStep: positionStep.value,
        comment: comment.value,
      }),
    )
    if (!started) return false
    const record = await guarded('deploy', () => api.deployment(started.deploymentId))
    attach(
      record ?? {
        id: started.deploymentId,
        environmentId: e.id,
        environmentName: e.name,
        bucketName: b.name,
        flows: [...selected.value],
        startedBy: '',
        startedAt: new Date().toISOString(),
        finishedAt: null,
        phase: 'Running',
        summary: null,
        comment: comment.value || null,
      },
    )
    return true
  }

  async function cancelDeployment() {
    if (!deploymentId.value || finished.value) return
    cancelling.value = true
    await guarded('deploy', () => api.cancel(deploymentId.value))
  }

  async function loadRecent() {
    try {
      recent.value = await api.recentDeployments()
    } catch {
      /* history is a nice-to-have */
    }
  }

  /** After a page reload: if a deployment was running, jump straight back to it. */
  async function restoreActive() {
    let id: string | null = null
    try {
      id = localStorage.getItem(ACTIVE_KEY)
    } catch {
      return
    }
    if (!id) return
    try {
      attach(await api.deployment(id))
    } catch {
      remember(null)
    }
  }

  async function init() {
    await loadEnvironments()
    await Promise.all([loadRecent(), restoreActive()])
  }

  // ---------- navigation ----------
  function resetFrom(fromStep: number) {
    if (fromStep <= 1) {
      registryClients.value = []
      buckets.value = []
      createdBucket.value = null
      expectedBucketFound.value = null
    }
    if (fromStep <= 2) {
      flows.value = []
      selected.value = []
    }
    if (fromStep <= 3) {
      paramContexts.value = {}
      overrides.value = {}
    }
    maxStep.value = Math.min(maxStep.value, fromStep)
  }

  const canNext = computed(() => {
    switch (step.value) {
      case 0:
        return connectionOk.value
      case 1:
        return !!bucket.value && !!registryClientId.value
      case 2:
        return selected.value.length > 0
      case 3:
        return !loading.params
      case 4:
        return !loading.deploy
      default:
        return finished.value
    }
  })

  const footerHint = computed(() => {
    switch (step.value) {
      case 0:
        return connectionOk.value ? 'Connection OK' : 'Run the connection check to continue'
      case 1:
        if (!registryClientId.value) return 'This NiFi needs a NiFi Registry client'
        return bucket.value ? `Bucket ${bucket.value.name}` : 'Create or select a bucket to continue'
      case 2:
        return `${selected.value.length} of ${flows.value.length} flows selected`
      case 3:
        return 'Changed values are applied to the flow before upload'
      case 4:
        return 'Nothing is changed until you start the deployment'
      default:
        return finished.value
          ? `Deployment ${phase.value?.toLowerCase()}`
          : 'Runs on the server — safe to close this tab and come back'
    }
  })

  async function enter(i: number) {
    step.value = i
    maxStep.value = Math.max(maxStep.value, i)
    if (i === 1 && !registryClients.value.length) await loadRegistry()
    if (i === 2 && !flows.value.length) await loadFlows()
    if (i === 3) await loadParameters()
  }

  async function next() {
    if (!canNext.value) return
    if (step.value === 4) {
      await startDeployment()
      return
    }
    if (step.value === 5) return newDeployment()
    await enter(step.value + 1)
  }

  function back() {
    if (step.value > 0 && step.value < 5) step.value -= 1
  }

  function goTo(i: number) {
    if (step.value === 5 && !finished.value) return
    if (i <= maxStep.value && i < 5) enter(i)
  }

  function newDeployment() {
    unsubscribe?.()
    unsubscribe = null
    deploymentId.value = ''
    deploymentMeta.value = null
    phase.value = null
    summary.value = null
    checks.value = []
    resetFrom(1)
    newBucketName.value = env.value?.defaultBucket ?? ''
    step.value = 0
    maxStep.value = 0
  }

  return {
    // state
    step, maxStep, error, loading,
    environments, envId, env, checks, connectionOk,
    registryClients, registryClientId, buckets, bucketMode, expectedBucketFound,
    newBucketName, newBucketDesc, createdBucket, selectedBucketId, bucket,
    flows, selected, selectedFlows,
    paramContexts, overrides,
    startFlow, positionStep, comment, plan,
    deploymentId, deploymentMeta, phase, logs, progress, stageDetail, summary, finished, percent, cancelling, recent,
    canNext, footerHint,
    // actions
    init, loadEnvironments, selectEnv, runChecks, loadRegistry, createRegistryClient, createBucket, loadFlows, toggleFlow,
    paramValue, setParam, next, back, goTo, newDeployment, attach, cancelDeployment, loadRecent,
  }
})
