
import axios from 'axios'
import { environments } from '../config/environments'

const env = environments[import.meta.env.VITE_ENV || "DEV"]
const NIFI = env.nifi

export async function getParameterContexts(){
 const r = await axios.get(`${NIFI}/nifi-api/flow/parameter-contexts`)
 return r.data.parameterContexts
}

export async function updateParameterContext(id,payload){
 return axios.put(`${NIFI}/nifi-api/parameter-contexts/${id}`,payload)
}

export async function importProcessGroup(payload){
 return axios.post(`${NIFI}/nifi-api/process-groups/root/process-groups`,payload)
}

export async function updateProcessGroup(pgId,payload){
 return axios.post(`${NIFI}/nifi-api/versions/update-requests/process-groups/${pgId}`,payload)
}
