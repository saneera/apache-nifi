
import axios from "axios"

const REG_URL=import.meta.env.VITE_REGISTRY_URL
const BUCKET=import.meta.env.VITE_REGISTRY_BUCKET

export async function getRegistryFlows(){
const res=await axios.get(
`${REG_URL}/nifi-registry-api/buckets/${BUCKET}/flows`
)
return res.data
}
