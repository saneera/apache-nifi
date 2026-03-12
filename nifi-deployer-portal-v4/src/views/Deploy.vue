<script setup lang="ts">

import { ref } from "vue"
import FlowUploader from "../components/FlowUploader.vue"
import FlowDiffViewer from "../components/FlowDiffViewer.vue"
import { getRegistryFlows, getLatestFlow } from "../api/registryApi"

const localFlow = ref(null)
const registryFlow = ref(null)

async function handleFlowUpload(flow:any){

  localFlow.value = flow

  const flows = await getRegistryFlows()

  const name = flow.flowContents.name

  const registryMeta = flows.find((f:any)=>f.name === name)

  if(!registryMeta){

    console.log("Flow not found in registry")

    registryFlow.value = null
    return

  }

  const version = await getLatestFlow(registryMeta.identifier)

  registryFlow.value = version.flowContents

  console.log("Local flow", localFlow.value)
  console.log("Registry flow", registryFlow.value)

}

</script>

<template>

  <h1 class="text-2xl font-bold mb-6">
    Deploy Flow
  </h1>

  <FlowUploader @flow-loaded="handleFlowUpload"/>

  <FlowDiffViewer
      :localFlow="localFlow"
      :registryFlow="registryFlow"
  />

</template>
