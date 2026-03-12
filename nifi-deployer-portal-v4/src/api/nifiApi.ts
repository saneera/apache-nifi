
import axios from "axios"

const NIFI_URL=import.meta.env.VITE_NIFI_URL
let token=""

export async function login(){
const res=await axios.post(
`${NIFI_URL}/nifi-api/access/token`,
new URLSearchParams({
username:import.meta.env.VITE_NIFI_USER,
password:import.meta.env.VITE_NIFI_PASSWORD
}),
{headers:{ "Content-Type":"application/x-www-form-urlencoded"}}
)

token=res.data
}

export function client(){
return axios.create({
baseURL:`${NIFI_URL}/nifi-api`,
headers:{Authorization:`Bearer ${token}`}
})
}

export async function getRootFlows(){
const c=client()
const res=await c.get("/flow/process-groups/root")
return res.data.processGroupFlow.flow.processGroups
}


export async function deployToNiFi(name:string, flowId:string, version:number){

    const payload = {

        revision:{version:0},

        component:{
            name,
            position:{x:400,y:300},

            versionControlInformation:{
                registryId:import.meta.env.VITE_REGISTRY_ID,
                bucketId:import.meta.env.VITE_REGISTRY_BUCKET,
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
