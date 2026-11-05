import { defineStore } from 'pinia'
import { computed, reactive, ref, watch } from 'vue'
import {
  api,
  STAGES,
  type Bucket,
  type CheckResult,
  type EnvironmentInfo,
  type FlowStatus,
  type JobPhase,
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
    desc: 'Pick the NiFi instance to deploy to. URLs come from the environment profile; credentials stay in the Kubernetes Secret.',
  },
  {
    label: 'Registry & bucket',
    sub: 'Find or create bucket',
    title: 'Registry client and bucket',
    desc: 'Flows are versioned in NiFi Registry. If the bucket does not exist yet, create it here and its ID is captured for the job.',
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
    desc: 'Check the plan and the environment variables the Kubernetes Job will run with. Nothing changes until you start.',
  },
  {
    label: 'Deploy',
    sub: 'Live progress',
    title: 'Deployment',
    desc: 'The Job runs the deploy script. Progress is read from its log stream.',
  },
] as const

type Loadable = 'envs' | 'check' | 'registry' | 'bucket' | 'flows' | 'params' | 'deploy'

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

  const jobEnvPreview = computed(() => {
    const e = env.value
    if (!e) return []
    return [
      ['NIFI_URL', e.nifiUrl],
      ['REGISTRY_URL', e.registryUrl],
      ['TARGET_RPG_URL', e.rpgUrl],
      ['REGISTRY_ID', registryClientId.value],
      ['REGISTRY_BUCKET_ID', bucket.value?.id ?? ''],
      ['BUCKET_NAME', bucket.value?.name ?? ''],
      ['FLOWS', selected.value.join(',')],
      ['START_FLOW', String(startFlow.value)],
      ['POSITION_STEP', String(positionStep.value)],
      ['DEPLOY_COMMENT', comment.value],
      ['PARAM_OVERRIDES', JSON.stringify(overrides.value)],
      ['SINGLE_USER_CREDENTIALS_*', `← ${e.credentialsRef}`],
    ] as const
  })

  // ---------- step 6: deploy ----------
  const jobName = ref('')
  const podName = ref('')
  const phase = ref<JobPhase | null>(null)
  const logs = ref<string[]>([])
  const progress = ref<Record<string, Record<Stage, StageStatus>>>({})
  const stageDetail = ref<Record<string, string>>({})
  const summary = ref<SummaryEvent | null>(null)
  let unsubscribe: (() => void) | null = null

  const finished = computed(() => phase.value === 'Succeeded' || phase.value === 'Failed')

  const percent = computed(() => {
    const flowsList = Object.values(progress.value)
    const total = flowsList.length * STAGES.length
    if (!total) return 0
    const doneCount = flowsList.reduce(
      (acc, st) => acc + STAGES.filter((s) => st[s] === 'done' || st[s] === 'skipped').length,
      0,
    )
    return finished.value && phase.value === 'Succeeded' ? 100 : Math.round((doneCount / total) * 100)
  })

  async function startDeployment() {
    const e = env.value
    const b = bucket.value
    if (!e || !b) return
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

    jobName.value = started.jobName
    logs.value = []
    summary.value = null
    phase.value = 'Pending'
    stageDetail.value = {}
    progress.value = Object.fromEntries(
      selected.value.map((f) => [f, Object.fromEntries(STAGES.map((s) => [s, 'pending'])) as Record<Stage, StageStatus>]),
    )

    unsubscribe?.()
    unsubscribe = api.subscribe(started, {
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
      onPhase: (ph, pod) => {
        phase.value = ph
        if (pod) podName.value = pod
      },
      onSummary: (s) => (summary.value = s),
      onError: (m) => (error.value = m),
    })
    return true
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
        return bucket.value ? `Bucket ${bucket.value.name}` : 'Create or select a bucket to continue'
      case 2:
        return `${selected.value.length} of ${flows.value.length} flows selected`
      case 3:
        return 'Changed values are passed to the job as PARAM_OVERRIDES'
      case 4:
        return 'Nothing is changed until you start the deployment'
      default:
        return finished.value ? `Job ${phase.value?.toLowerCase()}` : 'Job running…'
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
      if (await startDeployment()) enter(5)
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
    jobName.value = ''
    phase.value = null
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
    startFlow, positionStep, comment, jobEnvPreview,
    jobName, podName, phase, logs, progress, stageDetail, summary, finished, percent,
    canNext, footerHint,
    // actions
    loadEnvironments, selectEnv, runChecks, loadRegistry, createBucket, loadFlows, toggleFlow,
    paramValue, setParam, next, back, goTo, newDeployment,
  }
})
