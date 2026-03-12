
import axios from "axios"

const REG_URL=import.meta.env.VITE_REGISTRY_URL
const BUCKET=import.meta.env.VITE_REGISTRY_BUCKET

export async function getRegistryFlows(){
const res=await axios.get(
`${REG_URL}/nifi-registry-api/buckets/${BUCKET}/flows`
)
return res.data
}


export async function getLatestFlow(bucketId:string, flowId:string){

    const res = await axios.get(
        `${REG_URL}/nifi-registry-api/buckets/${bucketId}/flows/${flowId}/versions/latest`
    )

    return res.data

}

export async function createRegistryFlow(name:string){

    const res = await axios.post(
        `${REG_URL}/nifi-registry-api/buckets/${BUCKET}/flows`,
        {
            name,
            description:"created-from-ui"
        }
    )

    return res.data

}
