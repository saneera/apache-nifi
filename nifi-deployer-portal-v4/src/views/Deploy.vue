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

  if(!flowId.value){

    console.log("New flow deployment")

    return

  }

  const payload = flow

  await uploadRegistryVersion(flowId.value, 1, payload)

  console.log("Registry version uploaded")

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
