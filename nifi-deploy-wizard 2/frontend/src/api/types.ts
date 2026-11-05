// Shared API contract — mirrors the Java records in backend/src/main/java/.../web/dto

export interface EnvironmentInfo {
  id: string
  name: string
  description: string
  cluster: string
  nifiUrl: string
  registryUrl: string
  rpgUrl: string
  credentialsRef: string
  defaultBucket: string
  /** Hex colour used for the environment pill (e.g. red/black side). */
  color: string
}

export interface CheckResult {
  key: string
  label: string
  ok: boolean
  detail: string
}

export interface RegistryClient {
  id: string
  name: string
}

export interface Bucket {
  id: string
  name: string
  description?: string
  flowCount: number
}

export interface BucketResolveResult {
  found: boolean
  bucket?: Bucket
}

export interface CreateBucketRequest {
  name: string
  description?: string
}

export interface CreateBucketResult {
  /** false when a bucket with that name already existed and was reused. */
  created: boolean
  bucket: Bucket
}

export type FlowChange = 'NEW' | 'CHANGED' | 'UNCHANGED'

export interface FlowStatus {
  name: string
  file: string
  status: FlowChange
  localHash: string
  registryHash: string | null
  registryVersion: number | null
  parameterContext: string | null
}

export interface Parameter {
  name: string
  value: string | null
  sensitive: boolean
  description?: string
}

export interface ParameterContext {
  name: string
  parameters: Parameter[]
}

export interface DeploymentRequest {
  environmentId: string
  registryClientId: string
  bucketId: string
  bucketName: string
  flows: string[]
  /** flowName -> paramName -> value. Only non-sensitive values are ever sent. */
  parameterOverrides: Record<string, Record<string, string>>
  startFlow: boolean
  positionStep: number
  comment: string
}

export interface DeploymentStarted {
  deploymentId: string
}

export const STAGES = [
  'PREPARE',
  'HASH',
  'REGISTRY_UPLOAD',
  'IMPORT',
  'CONTROLLER_SERVICES',
  'PARAMETERS',
  'START',
] as const
export type Stage = (typeof STAGES)[number]

export const STAGE_LABELS: Record<Stage, string> = {
  PREPARE: 'Prepare',
  HASH: 'Hash',
  REGISTRY_UPLOAD: 'Registry upload',
  IMPORT: 'Import / update',
  CONTROLLER_SERVICES: 'Controller services',
  PARAMETERS: 'Parameters',
  START: 'Start',
}

export type StageStatus = 'pending' | 'started' | 'done' | 'failed' | 'skipped'

export interface ProgressEvent {
  flow: string
  stage: Stage
  status: Exclude<StageStatus, 'pending'>
  detail?: string
}

export type DeployPhase = 'Running' | 'Succeeded' | 'Failed' | 'Cancelled'

export interface SummaryEvent {
  total: number
  deployed: number
  skipped: number
  failed: number
}

/** A deployment as shown in history / the audit log. */
export interface DeploymentRecord {
  id: string
  environmentId: string
  environmentName: string
  bucketName: string
  flows: string[]
  startedBy: string
  startedAt: string
  finishedAt: string | null
  phase: DeployPhase
  summary: SummaryEvent | null
  comment: string | null
}

export interface DeploymentEventHandlers {
  onLog: (line: string) => void
  onProgress: (e: ProgressEvent) => void
  onPhase: (phase: DeployPhase) => void
  onSummary: (s: SummaryEvent) => void
  onError: (message: string) => void
}

export interface DeployApi {
  environments(): Promise<EnvironmentInfo[]>
  check(envId: string): Promise<CheckResult[]>
  registryClients(envId: string): Promise<RegistryClient[]>
  buckets(envId: string): Promise<Bucket[]>
  resolveBucket(envId: string, name: string): Promise<BucketResolveResult>
  createBucket(envId: string, req: CreateBucketRequest): Promise<CreateBucketResult>
  flows(envId: string, bucketId: string | null): Promise<FlowStatus[]>
  parameters(flowName: string): Promise<ParameterContext | null>
  deploy(req: DeploymentRequest): Promise<DeploymentStarted>
  deployment(id: string): Promise<DeploymentRecord>
  recentDeployments(): Promise<DeploymentRecord[]>
  /** Stops after the flow currently being deployed. */
  cancel(id: string): Promise<void>
  /** Replays the deployment's events so far, then streams live. Returns an unsubscribe function. */
  subscribe(deploymentId: string, handlers: DeploymentEventHandlers): () => void
}
