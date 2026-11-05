import type {
  Bucket,
  BucketResolveResult,
  CheckResult,
  CreateBucketRequest,
  CreateBucketResult,
  DeployApi,
  DeploymentEventHandlers,
  DeploymentRequest,
  DeploymentStarted,
  EnvironmentInfo,
  FlowStatus,
  DeployPhase,
  DeploymentRecord,
  ParameterContext,
  ProgressEvent,
  RegistryClient,
  SummaryEvent,
} from './types'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (res.status === 204) return undefined as T
  const text = await res.text()
  const data = text ? JSON.parse(text) : undefined
  if (!res.ok) {
    const msg = (data && (data.message || data.detail || data.error)) || `${res.status} ${res.statusText}`
    throw new ApiError(msg, res.status)
  }
  return data as T
}

const enc = encodeURIComponent

export const httpApi: DeployApi = {
  environments: () => request<EnvironmentInfo[]>('GET', '/api/environments'),
  check: (envId) => request<CheckResult[]>('POST', `/api/environments/${enc(envId)}/check`),
  registryClients: (envId) => request<RegistryClient[]>('GET', `/api/environments/${enc(envId)}/registry-clients`),
  buckets: (envId) => request<Bucket[]>('GET', `/api/environments/${enc(envId)}/buckets`),
  resolveBucket: (envId, name) =>
    request<BucketResolveResult>('GET', `/api/environments/${enc(envId)}/buckets/resolve?name=${enc(name)}`),
  createBucket: (envId, req: CreateBucketRequest) =>
    request<CreateBucketResult>('POST', `/api/environments/${enc(envId)}/buckets`, req),
  flows: (envId, bucketId) =>
    request<FlowStatus[]>(
      'GET',
      `/api/environments/${enc(envId)}/flows${bucketId ? `?bucketId=${enc(bucketId)}` : ''}`,
    ),
  parameters: (flowName) => request<ParameterContext | null>('GET', `/api/flows/${enc(flowName)}/parameters`),
  deploy: (req: DeploymentRequest) => request<DeploymentStarted>('POST', '/api/deployments', req),
  deployment: (id) => request<DeploymentRecord>('GET', `/api/deployments/${enc(id)}`),
  recentDeployments: () => request<DeploymentRecord[]>('GET', '/api/deployments'),
  cancel: (id) => request<void>('POST', `/api/deployments/${enc(id)}/cancel`),

  subscribe(deploymentId: string, h: DeploymentEventHandlers) {
    const es = new EventSource(`/api/deployments/${enc(deploymentId)}/events`)
    let finished = false
    es.addEventListener('log', (e) => h.onLog(JSON.parse((e as MessageEvent).data).line))
    es.addEventListener('progress', (e) => h.onProgress(JSON.parse((e as MessageEvent).data) as ProgressEvent))
    es.addEventListener('summary', (e) => h.onSummary(JSON.parse((e as MessageEvent).data) as SummaryEvent))
    es.addEventListener('phase', (e) => {
      const { phase } = JSON.parse((e as MessageEvent).data) as { phase: DeployPhase }
      h.onPhase(phase)
      if (phase !== 'Running') {
        finished = true
        es.close()
      }
    })
    es.onerror = () => {
      // EventSource reconnects by itself (the server replays history, so nothing is lost).
      if (!finished && es.readyState === EventSource.CLOSED) h.onError('Lost connection to the deployment stream')
    }
    return () => es.close()
  },
}
