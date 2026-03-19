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
},

    async registryFlow(flowId: string) {
        const store = useNifiStore()

        // get latest version
        const res = await api.get(
            `/nifi-registry-api/buckets/${store.bucketId}/flows/${flowId}/versions/latest`
        )

        return res.data
    },

  async updateParameterContext(id: string, payload: any) {
    const s = useNifiStore()
    return api.put(`${s.nifiUrl}/nifi-api/parameter-contexts/${id}`, payload, { headers: this.headers() })
  },

    async flowVersions(flowId: string) {,
        const store = useNifiStore()

        const res = await api.get(
            `/nifi-registry-api/buckets/${store.bucketId}/flows/${flowId}/versions`
        )

        return res.data
    },

    async getCurrentVersion(flowName: string) {
        const store = useNifiStore()

        const res = await api.get(
            `${store.nifiUrl}/nifi-api/flow/process-groups/root`
        )

        const pg = res.data.processGroupFlow.flow.processGroups.find(
            (p: any) => p.component.name === flowName
        )

        if (!pg) return null

        return pg.component.versionControlInformation?.version || null
    }
}
