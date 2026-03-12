
import { getFlows,createFlow,uploadVersion,getVersions } from '../api/registryApi'
import { calculateHash } from '../utils/hashUtil'

export async function deployFlow(flow, progress){

 const hash = calculateHash(flow)

 progress.value = "Checking registry..."

 const flows = await getFlows()
 let meta = flows.find(f=>f.name===flow.flowContents.name)

 let flowId

 if(!meta){
  progress.value="Creating registry flow..."
  const created = await createFlow(flow.flowContents.name)
  flowId = created.identifier
 } else {
  flowId = meta.identifier

  const versions = await getVersions(flowId)
  const latest = versions[versions.length-1]

  if(latest && latest.snapshotMetadata?.comments === hash){
    progress.value="Flow unchanged. Skipping deployment."
    return
  }
 }

 progress.value="Uploading version..."
 flow.snapshotMetadata = flow.snapshotMetadata || {}
 flow.snapshotMetadata.comments = hash

 await uploadVersion(flowId,flow)

 progress.value="Deployment complete"

 const history = JSON.parse(localStorage.getItem("deployHistory") || "[]")
 history.push({
   flow: flow.flowContents.name,
   time: new Date().toISOString(),
   hash
 })
 localStorage.setItem("deployHistory", JSON.stringify(history))
}
