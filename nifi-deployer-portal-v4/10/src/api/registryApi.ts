
import axios from 'axios'
import { environments } from '../config/environments'

const env = environments[import.meta.env.VITE_ENV || "DEV"]
const REG = env.registry
const BUCKET = env.bucket

export async function getFlows(){
 const r = await axios.get(`${REG}/nifi-registry-api/buckets/${BUCKET}/flows`)
 return r.data
}

export async function getVersions(flowId){
 const r = await axios.get(`${REG}/nifi-registry-api/buckets/${BUCKET}/flows/${flowId}/versions`)
 return r.data
}

export async function uploadVersion(flowId,payload){
 return axios.post(`${REG}/nifi-registry-api/buckets/${BUCKET}/flows/${flowId}/versions`,payload)
}

export async function createFlow(name){
 const r = await axios.post(`${REG}/nifi-registry-api/buckets/${BUCKET}/flows`,{name})
 return r.data
}
