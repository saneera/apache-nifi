// In-browser mock of the backend, used with `npm run dev:mock`.
// Lets you click through the whole wizard without NiFi, Registry or Kubernetes.
import {
  STAGES,
  type Bucket,
  type DeployApi,
  type DeploymentRequest,
  type EnvironmentInfo,
  type DeploymentRecord,
  type DeploymentEventHandlers,
  type FlowStatus,
  type ParameterContext,
} from './types'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const uuid = () =>
  'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })

const ENVS: EnvironmentInfo[] = [
  {
    id: 'black',
    name: 'Black NiFi',
    description: 'Black-side cluster · single pod',
    cluster: 'k8s-black / nifi-0',
    nifiUrl: 'https://nifi-black.example.svc:8443',
    registryUrl: 'https://registry-black.example.svc:18443',
    rpgUrl: 'https://nifi-red.example.svc:8443/nifi',
    credentialsRef: 'secret/nifi-single-user',
    defaultBucket: 'hfc2-black-flows',
    color: '#9BA8B8',
  },
  {
    id: 'red',
    name: 'Red NiFi',
    description: 'Red-side cluster · single pod',
    cluster: 'k8s-red / nifi-0',
    nifiUrl: 'https://nifi-red.example.svc:8443',
    registryUrl: 'https://registry-red.example.svc:18443',
    rpgUrl: 'https://nifi-black.example.svc:8443/nifi',
    credentialsRef: 'secret/nifi-single-user',
    defaultBucket: 'hfc2-red-flows',
    color: '#FF6B6B',
  },
]

const buckets: Record<string, Bucket[]> = {
  black: [
    { id: uuid(), name: 'shared-utilities', flowCount: 6 },
    { id: uuid(), name: 'sandbox', flowCount: 2 },
  ],
  red: [{ id: uuid(), name: 'shared-utilities', flowCount: 4 }],
}

const FLOWS: Omit<FlowStatus, 'status' | 'registryHash' | 'registryVersion'>[] = [
  { name: 'gateway-ingest', file: 'gateway-ingest.json', localHash: 'a91f3c0e', parameterContext: 'gateway-params' },
  { name: 'egress-router', file: 'egress-router.json', localHash: '5d02e7b9', parameterContext: 'egress-params' },
  { name: 'audit-forwarder', file: 'audit-forwarder.json', localHash: '0c8e41fa', parameterContext: 'audit-params' },
  { name: 'sftp-collector', file: 'sftp-collector.json', localHash: 'f3b9a612', parameterContext: 'sftp-params' },
  { name: 'heartbeat', file: 'heartbeat.json', localHash: '6a17d5c3', parameterContext: null },
]
// Registry state for a pre-existing bucket. A freshly created bucket is empty, so every flow is NEW.
const REGISTRY: Record<string, { hash: string; version: number }> = {
  'gateway-ingest': { hash: '7be2d410', version: 14 },
  'audit-forwarder': { hash: '0c8e41fa', version: 7 },
  'sftp-collector': { hash: '2e7c09d1', version: 3 },
  heartbeat: { hash: '6a17d5c3', version: 22 },
}
const createdBuckets = new Set<string>()

const PARAMS: Record<string, ParameterContext> = {
  'gateway-params': {
    name: 'gateway-params',
    parameters: [
      { name: 'listen.port', value: '9443', sensitive: false },
      { name: 'batch.size', value: '500', sensitive: false },
      { name: 'truststore.password', value: null, sensitive: true },
    ],
  },
  'egress-params': {
    name: 'egress-params',
    parameters: [
      { name: 'target.host', value: 'egress.example.svc', sensitive: false },
      { name: 'retry.max', value: '5', sensitive: false },
      { name: 'api.token', value: null, sensitive: true },
    ],
  },
  'audit-params': {
    name: 'audit-params',
    parameters: [
      { name: 'log.level', value: 'INFO', sensitive: false },
      { name: 'forward.url', value: 'https://audit.example.svc', sensitive: false },
    ],
  },
  'sftp-params': {
    name: 'sftp-params',
    parameters: [
      { name: 'sftp.host', value: 'sftp.example.svc', sensitive: false },
      { name: 'poll.interval', value: '30 sec', sensitive: false },
      { name: 'sftp.password', value: null, sensitive: true },
    ],
  },
}

// ---- simulated server-side deployments: run independently of any subscriber, events are replayable ----
type MockEvent =
  | { t: 'log'; line: string }
  | { t: 'progress'; flow: string; stage: (typeof STAGES)[number]; status: 'started' | 'done' | 'failed' | 'skipped'; detail?: string }
  | { t: 'summary'; total: number; deployed: number; skipped: number; failed: number }
  | { t: 'phase'; phase: DeploymentRecord['phase'] }

interface MockRun {
  record: DeploymentRecord
  events: MockEvent[]
  listeners: Set<(e: MockEvent) => void>
  cancel: boolean
}
const runs = new Map<string, MockRun>()

function emit(run: MockRun, e: MockEvent) {
  run.events.push(e)
  run.listeners.forEach((l) => l(e))
}

async function simulate(run: MockRun, req: DeploymentRequest) {
  const ts = () => new Date().toISOString().replace('T', ' ').slice(0, 19)
  const log = (lvl: string, msg: string) => emit(run, { t: 'log', line: `[${lvl}]${' '.repeat(Math.max(1, 10 - lvl.length - 2))}${ts()} ${msg}` })
  emit(run, { t: 'phase', phase: 'Running' })
  log('SECTION', `Deploying ${req.flows.length} flow(s) to ${run.record.environmentName} · started by ${run.record.startedBy}`)
  await sleep(400)
  log('INFO', 'NiFi API is ready')
  log('INFO', `Root PG 7f3a… · bucket ${req.bucketName} (${req.bucketId})`)
  let deployed = 0
  let skipped = 0
  let phase: DeploymentRecord['phase'] = 'Succeeded'
  for (const flow of req.flows) {
    if (run.cancel) {
      log('WARN', `Cancelled before ${flow}`)
      phase = 'Cancelled'
      break
    }
    log('SECTION', `Processing ${flow}`)
    const reg = createdBuckets.has(req.bucketId) ? undefined : REGISTRY[flow]
    const local = FLOWS.find((f) => f.name === flow)?.localHash
    for (const stage of STAGES) {
      emit(run, { t: 'progress', flow, stage, status: 'started' })
      if (stage === 'HASH' && reg && reg.hash === local) {
        await sleep(300)
        log('INFO', `No changes detected - skipping ${flow}`)
        emit(run, { t: 'progress', flow, stage, status: 'skipped', detail: 'no changes' })
        skipped++
        break
      }
      if (stage === 'CONTROLLER_SERVICES') {
        log('INFO', 'Enable request sent - polling...')
        for (let i = 1; i <= 3; i++) {
          await sleep(350)
          const line = `Poll ${i}/30: enabled=${i * 3 - 1} enabling=${9 - i * 3} disabled=0 total=8`
          log('INFO', line)
          emit(run, { t: 'progress', flow, stage, status: 'started', detail: line })
        }
        log('INFO', 'All controller services enabled')
      } else {
        await sleep(stage === 'IMPORT' ? 700 : 300)
      }
      const v = (reg?.version ?? 0) + 1
      const detail =
        stage === 'REGISTRY_UPLOAD' || stage === 'IMPORT' ? `v${v}` : stage === 'START' ? (req.startFlow ? 'running' : 'not started') : undefined
      if (stage === 'REGISTRY_UPLOAD') log('INFO', `Uploaded registry version ${v}`)
      emit(run, { t: 'progress', flow, stage, status: 'done', detail })
      if (stage === 'START') {
        log('INFO', `Done: ${flow}`)
        deployed++
      }
    }
  }
  log('SECTION', 'Deployment Summary')
  const summary = { total: req.flows.length, deployed, skipped, failed: 0 }
  log('INFO', `Total : ${summary.total}   Deployed : ${deployed}   Skipped : ${skipped}`)
  emit(run, { t: 'summary', ...summary })
  run.record = { ...run.record, phase, summary, finishedAt: new Date().toISOString() }
  emit(run, { t: 'phase', phase })
}

function dispatch(e: MockEvent, h: DeploymentEventHandlers) {
  if (e.t === 'log') h.onLog(e.line)
  else if (e.t === 'progress') h.onProgress({ flow: e.flow, stage: e.stage, status: e.status, detail: e.detail })
  else if (e.t === 'summary') h.onSummary({ total: e.total, deployed: e.deployed, skipped: e.skipped, failed: e.failed })
  else h.onPhase(e.phase)
}

const HISTORY: DeploymentRecord[] = [
  {
    id: 'dep-20261104-153012-a1x',
    environmentId: 'red',
    environmentName: 'Red NiFi',
    bucketName: 'hfc2-red-flows',
    flows: ['gateway-ingest', 'sftp-collector'],
    startedBy: 'saneera',
    startedAt: '2026-11-04T05:00:12Z',
    finishedAt: '2026-11-04T05:02:40Z',
    phase: 'Succeeded',
    summary: { total: 2, deployed: 2, skipped: 0, failed: 0 },
    comment: 'CR-1170',
  },
]

export const mockApi: DeployApi = {
  async environments() {
    await sleep(200)
    return ENVS
  },
  async check() {
    await sleep(900)
    return [
      { key: 'nifi', label: 'NiFi API reachable', ok: true, detail: 'POST /nifi-api/access/token' },
      { key: 'auth', label: 'Authenticated', ok: true, detail: 'single-user · token ok' },
      { key: 'registry', label: 'Registry reachable', ok: true, detail: 'GET /nifi-registry-api/buckets' },
      { key: 'root', label: 'Root process group', ok: true, detail: 'GET /flow/process-groups/root' },
    ]
  },
  async registryClients() {
    await sleep(200)
    return [{ id: 'a3f0c2e1-5d4b-4a7e-8c19-6f2b0e8d9b7d', name: 'hfc2-registry' }]
  },
  async buckets(envId) {
    await sleep(250)
    return buckets[envId] ?? []
  },
  async resolveBucket(envId, name) {
    await sleep(250)
    const b = (buckets[envId] ?? []).find((x) => x.name === name)
    return b ? { found: true, bucket: b } : { found: false }
  },
  async createBucket(envId, req) {
    await sleep(900)
    const list = (buckets[envId] ??= [])
    const existing = list.find((b) => b.name === req.name)
    if (existing) return { created: false, bucket: existing }
    const b: Bucket = { id: uuid(), name: req.name, description: req.description, flowCount: 0 }
    list.push(b)
    createdBuckets.add(b.id)
    return { created: true, bucket: b }
  },
  async flows(_envId, bucketId) {
    await sleep(500)
    const empty = !bucketId || createdBuckets.has(bucketId)
    return FLOWS.map((f) => {
      const reg = empty ? undefined : REGISTRY[f.name]
      return {
        ...f,
        registryHash: reg?.hash ?? null,
        registryVersion: reg?.version ?? null,
        status: !reg ? 'NEW' : reg.hash === f.localHash ? 'UNCHANGED' : 'CHANGED',
      }
    })
  },
  async parameters(flowName) {
    await sleep(150)
    const ctx = FLOWS.find((f) => f.name === flowName)?.parameterContext
    return ctx ? (PARAMS[ctx] ?? null) : null
  },
  async deploy(req) {
    await sleep(300)
    const env = ENVS.find((e) => e.id === req.environmentId)!
    const busy = [...runs.values()].find((r) => r.record.environmentId === env.id && r.record.phase === 'Running')
    if (busy) throw new Error(`A deployment is already running on ${env.name} (started by ${busy.record.startedBy})`)
    const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 14)
    const id = `dep-${stamp.slice(0, 8)}-${stamp.slice(8)}-${Math.random().toString(36).slice(2, 5)}`
    const run: MockRun = {
      record: {
        id,
        environmentId: env.id,
        environmentName: env.name,
        bucketName: req.bucketName,
        flows: req.flows,
        startedBy: 'you (mock)',
        startedAt: new Date().toISOString(),
        finishedAt: null,
        phase: 'Running',
        summary: null,
        comment: req.comment || null,
      },
      events: [],
      listeners: new Set(),
      cancel: false,
    }
    runs.set(id, run)
    simulate(run, req)
    return { deploymentId: id }
  },
  async deployment(id) {
    const run = runs.get(id)
    if (run) return run.record
    const past = HISTORY.find((h) => h.id === id)
    if (past) return past
    throw new Error('Unknown deployment')
  },
  async recentDeployments() {
    await sleep(150)
    return [...[...runs.values()].map((r) => r.record).reverse(), ...HISTORY]
  },
  async cancel(id) {
    const run = runs.get(id)
    if (run) {
      run.cancel = true
      emit(run, { t: 'log', line: '[WARN]    Cancel requested — stopping after the current flow' })
    }
  },
  subscribe(deploymentId, h) {
    const run = runs.get(deploymentId)
    if (!run) {
      h.onError('Deployment is no longer in memory')
      return () => {}
    }
    run.events.forEach((e) => dispatch(e, h))
    const l = (e: MockEvent) => dispatch(e, h)
    run.listeners.add(l)
    return () => run.listeners.delete(l)
  },
}
