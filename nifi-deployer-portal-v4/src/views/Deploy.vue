<script setup lang="ts">

import { ref } from "vue"
import FlowUploader from "../components/FlowUploader.vue"
import FlowDiffViewer from "../components/FlowDiffViewer.vue"
import { getRegistryFlows, getLatestFlow } from "../api/registryApi"
import { uploadRegistryVersion } from "../api/deployApi"

const localFlow = ref(null)
const registryFlow = ref(null)
const flowId = ref(null)

async function handleFlowUpload(flow:any){

  localFlow.value = flow

  const flows = await getRegistryFlows()

  const name = flow.flowContents.name

  const registryMeta = flows.find((f:any)=>f.name === name)

  if(!registryMeta){

    registryFlow.value = null
    return

  }

  flowId.value = registryMeta.identifier

  const version = await getLatestFlow(flowId.value)

  registryFlow.value = version.flowContents

}

async function deployFlow(flow:any){

  console.log("Deploying flow")

  const name = flow.flowContents.name

  let id = flowId.value

  // STEP 1: create registry flow if missing
  if(!id){

    console.log("Creating new registry flow")

    const created = await createRegistryFlow(name)

    id = created.identifier

    flowId.value = id

  }

  // STEP 2: get latest version
  const latest = await getLatestFlow(id)

  const version = latest?.snapshotMetadata?.version || 0

  const nextVersion = version + 1

  console.log("Uploading version", nextVersion)

  // STEP 3: upload registry version
  await uploadRegistryVersion(id,nextVersion,flow)

  // STEP 4: import or update NiFi
  await deployToNiFi(name,id,nextVersion)

  console.log("Deployment complete")

}

</script>

<template>

  <h1 class="text-2xl font-bold mb-6">
    Deploy Flow
  </h1>

  <FlowUploader
      @flow-loaded="handleFlowUpload"
      @deploy="deployFlow"
  />

  <FlowDiffViewer
      :localFlow="localFlow"
      :registryFlow="registryFlow"
  />

</template>
