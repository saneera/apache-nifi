import axios from "axios"

const REG_URL = import.meta.env.VITE_REGISTRY_URL
const NIFI_URL = import.meta.env.VITE_NIFI_URL
const BUCKET = import.meta.env.VITE_REGISTRY_BUCKET

export async function uploadRegistryVersion(flowId:string, version:number, payload:any){

    const res = await axios.post(
        `${REG_URL}/nifi-registry-api/buckets/${BUCKET}/flows/${flowId}/versions`,
        payload,
        {
            headers:{ "Content-Type":"application/json"}
        }
    )

    return res.data

}

export async function importFlow(name:string, registryId:string, bucketId:string, flowId:string, version:number){

    const payload = {

        revision:{version:0},

        component:{
            name,
            position:{x:400,y:300},

            versionControlInformation:{
                registryId,
                bucketId,
                flowId,
                version
            }

        }

    }

    const res = await axios.post(
        `${NIFI_URL}/nifi-api/process-groups/root/process-groups`,
        payload
    )

    return res.data

}

export async function updateFlowVersion(pgId:string, version:number, flowId:string, bucketId:string, registryId:string, revision:number){

    const payload = {

        disconnectedNodeAcknowledged:false,

        processGroupRevision:{version:revision},

        versionControlInformation:{
            version,
            flowId,
            bucketId,
            registryId,
            groupId:pgId
        }

    }

    const res = await axios.post(
        `${NIFI_URL}/nifi-api/versions/update-requests/process-groups/${pgId}`,
        payload
    )

    return res.data

}
