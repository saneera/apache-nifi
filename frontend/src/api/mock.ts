// In-browser mock of the backend, used with `npm run dev:mock`.
// Lets you click through the whole wizard without NiFi, Registry or Kubernetes.
import {
  STAGES,
  type Bucket,
  type DeployApi,
  type DeploymentRequest,
  type EnvironmentInfo,
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

const jobs = new Map<string, DeploymentRequest>()

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
    await sleep(400)
    const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 12)
    const jobName = `nifi-deploy-${stamp}`
    jobs.set(jobName, req)
    return { jobName, namespace: 'hfc2-nifi' }
  },

  subscribe(job, h) {
    const jobName = job.jobName
    const req = jobs.get(jobName)
    let cancelled = false
    const ts = () => new Date().toISOString().replace('T', ' ').slice(0, 19)
    const run = async () => {
      if (!req) return h.onError('Unknown job')
      h.onPhase('Pending')
      await sleep(600)
      h.onPhase('Running', `${jobName}-x7k2p`)
      const log = (lvl: string, msg: string) => h.onLog(`[${lvl}]${lvl === 'INFO' ? '   ' : ' '} ${ts()} ${msg}`)
      log('SECTION', 'Waiting for NiFi API')
      await sleep(400)
      log('INFO', 'NiFi API is ready')
      log('INFO', `Bucket ID: ${req.bucketId}`)
      for (const flow of req.flows) {
        for (const stage of STAGES) {
          if (cancelled) return
          h.onProgress({ flow, stage, status: 'started' })
          await sleep(stage === 'CONTROLLER_SERVICES' ? 900 : 350)
          const detail =
            stage === 'CONTROLLER_SERVICES'
              ? 'Poll 3/30: enabled=8 enabling=0 disabled=0 total=8'
              : stage === 'START' && !req.startFlow
                ? 'left STOPPED (START_FLOW=false)'
                : undefined
          if (stage === 'PREPARE') log('SECTION', `Processing ${flow}`)
          else log('INFO', `${flow}: ${stage.toLowerCase().replace('_', ' ')} ${detail ?? 'ok'}`)
          h.onProgress({ flow, stage, status: 'done', detail })
        }
        log('INFO', `Done: ${flow}`)
      }
      log('SECTION', 'Deployment Summary')
      const total = req.flows.length
      h.onSummary({ total, deployed: total, skipped: 0 })
      h.onPhase('Succeeded')
    }
    run()
    return () => {
      cancelled = true
    }
  },
}
