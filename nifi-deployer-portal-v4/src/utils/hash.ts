
import sha256 from "crypto-js/sha256"

export function calculateFlowHash(flow:any){
 return sha256(JSON.stringify(flow)).toString()
}

export function calculateParamHash(params:any){
 return sha256(JSON.stringify(params ?? {})).toString()
}

export function combinedHash(flow:string,param:string){
 return `${flow}_${param}`
}
