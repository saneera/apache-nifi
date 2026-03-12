
import CryptoJS from 'crypto-js'

export function calculateHash(flow){
 const content = JSON.stringify(flow.flowContents || flow)
 return CryptoJS.SHA256(content).toString()
}
