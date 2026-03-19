async deployFlow(flowName: string, flowJson: any) {
    const store = useNifiStore()

    // 1️⃣ get registry flows
    const flowsRes = await api.get(
        `${store.registryUrl}/nifi-registry-api/buckets/${store.bucketId}/flows`
    )

    let flow = flowsRes.data.find((f: any) => f.name === flowName)

    // 2️⃣ create registry flow if not exists
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

    // 3️⃣ get latest version (handle 404)
    let currentVersion = 0

    try {
        const latest = await api.get(
            `${store.registryUrl}/nifi-registry-api/buckets/${store.bucketId}/flows/${flow.identifier}/versions/latest`
        )

        currentVersion = latest.data.snapshotMetadata?.version || 0
    } catch (e: any) {
        if (e.response?.status !== 404) throw e
    }

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

    // 🟢 CASE 1: PG exists → update version
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

        return { version: nextVersion, action: 'updated' }
    }

    // 🔥 CASE 2: PG NOT found → import flow (CREATE)
    const importPayload = {
        revision: { version: 0 },
        component: {
            name: flowName,
            position: { x: 300, y: 300 },
            versionControlInformation: {
                registryId: store.registryId,
                bucketId: store.bucketId,
                flowId: flow.identifier,
                version: nextVersion
            }
        }
    }

    await api.post(
        `${store.nifiUrl}/nifi-api/process-groups/root/process-groups`,
        importPayload
    )

    return { version: nextVersion, action: 'created' }
}
