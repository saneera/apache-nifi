import axios from 'axios'
import { useNifiStore } from '../store/nifi'
import api from "./api";




export const nifiService = {
  headers() {
    const s = useNifiStore()
    return { Authorization: `Bearer ${s.token}` }
  },

async flows() {
    const store = useNifiStore()
    const r = await api.get(
        `${store.nifiUrl}/nifi-api/flow/process-groups/root`,
        { headers: this.headers() }
    )

    return r.data.processGroupFlow.flow.processGroups
 },

  async toggle(id: string, state: string) {
    const s = useNifiStore()
    return api.put(`${s.nifiUrl}/nifi-api/flow/process-groups/${id}`, { id, state }, { headers: this.headers() })
  },

  async registryFlows() {
    const s = useNifiStore()
    return api.get(`${s.registryUrl}/nifi-registry-api/buckets/${s.registryBucket}/flows`)
  },

  async registryVersions(flowId: string) {
    const s = useNifiStore()
    return api.get(`${s.registryUrl}/nifi-registry-api/flows/${flowId}/versions`)
  },


  async parameterContexts() {
    const store = useNifiStore()

    const res = await api.get(
        `${store.nifiUrl}/nifi-api/flow/parameter-contexts`
    )

    return res.data.parameterContexts
},

async parameterContext(id: string) {
    const store = useNifiStore()

    const res = await api.get(
        `${store.nifiUrl}/nifi-api/parameter-contexts/${id}`
    )

    return res.data.component
}

  async updateParameterContext(id: string, payload: any) {
    const s = useNifiStore()
    return api.put(`${s.nifiUrl}/nifi-api/parameter-contexts/${id}`, payload, { headers: this.headers() })
  },

  // optional: deploy trigger endpoint (if you later add a gateway)
  async triggerDeploy(flowName: string) {
    const s = useNifiStore()
    const url = import.meta.env.VITE_DEPLOY_URL
    if (!url) throw new Error('VITE_DEPLOY_URL not set')
    return api.post(`${url}/deploy/${encodeURIComponent(flowName)}`, {}, { headers: this.headers() })
  }
}
