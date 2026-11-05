import { httpApi } from './http'
import { mockApi } from './mock'
import type { DeployApi } from './types'

export const api: DeployApi = import.meta.env.VITE_MOCK === 'true' ? mockApi : httpApi
export const isMock = import.meta.env.VITE_MOCK === 'true'
export * from './types'
