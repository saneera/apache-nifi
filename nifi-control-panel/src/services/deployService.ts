import api from './api'
import { useNifiStore } from '../store/nifi'

export const deployService = {

    async deployFlow(flowName: string, flowJson: any) {
        const store = useNifiStore()

        // 1️⃣ find registry flow
        const flows = await api.get(
            `${store.registryUrl}/nifi-registry-api/buckets/${store.bucketId}/flows`
        )

        let flow = flows.data.find((f: any) => f.name === flowName)

        // 2️⃣ create if not exists
        if (!flow) {
            const created = await api.post(
                `${store.registryUrl}/nifi-registry-api/buckets/${store.bucketId}/flows`,
                {
                    name: flowName,
                    description: 'created via UI'
                }
            )
            flow = created.data
        }

        // 3️⃣ get latest version
        const latest = await api.get(
            `${store.registryUrl}/nifi-registry-api/buckets/${store.bucketId}/flows/${flow.identifier}/versions/latest`
        )

        const currentVersion = latest.data.snapshotMetadata?.version || 0
        const nextVersion = currentVersion + 1

        // 4️⃣ upload new version
        await api.post(
            `${store.registryUrl}/nifi-registry-api/buckets/${store.bucketId}/flows/${flow.identifier}/versions`,
            {
                ...flowJson,
                snapshotMetadata: {
                    bucketIdentifier: store.bucketId,
                    flowIdentifier: flow.identifier,
                    version: nextVersion
                }
            }
        )

        // 5️⃣ find PG in NiFi
        const root = await api.get(
            `${store.nifiUrl}/nifi-api/flow/process-groups/root`
        )

        const pg = root.data.processGroupFlow.flow.processGroups.find(
            (p: any) => p.component.name === flowName
        )

        // 6️⃣ update version
        if (pg) {
            await api.post(
                `${store.nifiUrl}/nifi-api/versions/update-requests/process-groups/${pg.component.id}`,
                {
                    processGroupRevision: {
                        version: pg.revision.version
                    },
                    versionControlInformation: {
                        registryId: store.registryId,
                        bucketId: store.bucketId,
                        flowId: flow.identifier,
                        version: nextVersion
                    }
                }
            )
        }

        return {
            version: nextVersion
        }
    },

    async rollbackFlow(flowName: string, version: number) {
        const store = useNifiStore()

        // 1️⃣ find PG
        const root = await api.get(
            `/nifi-api/flow/process-groups/root`
        )

        const pg = root.data.processGroupFlow.flow.processGroups.find(
            (p: any) => p.component.name === flowName
        )

        if (!pg) throw new Error('Process group not found')

        // 2️⃣ update version
        await api.post(
            `/nifi-api/versions/update-requests/process-groups/${pg.component.id}`,
            {
                processGroupRevision: {
                    version: pg.revision.version
                },
                versionControlInformation: {
                    registryId: store.registryId,
                    bucketId: store.bucketId,
                    flowId: pg.component.versionControlInformation.flowId,
                    version: version
                }
            }
        )

        return { version }
    }
}
