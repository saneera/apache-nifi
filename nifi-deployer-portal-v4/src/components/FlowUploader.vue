
<script setup lang="ts">

import { ref } from "vue"
import { calculateFlowHash,calculateParamHash,combinedHash } from "../utils/hash"

const emit = defineEmits(["flow-loaded"])


const flowName=ref("")
const flowHash=ref("")
const paramHash=ref("")
const localHash=ref("")

function upload(event:any){

  const file=event.target.files[0]

  const reader=new FileReader()

  reader.onload=()=>{

    const json=JSON.parse(reader.result as string)

    emit("flow-loaded", json)

    flowName.value=json.flowContents.name
    flowHash.value=calculateFlowHash(json.flowContents)
    paramHash.value=calculateParamHash(json.parameterContexts)
    localHash.value=combinedHash(flowHash.value,paramHash.value)

  }

  reader.readAsText(file)

}

</script>

<template>

<div class="bg-white p-6 rounded shadow max-w-xl">

<input type="file" @change="upload" class="mb-4"/>

<div v-if="flowName" class="space-y-2">

<p><b>Flow:</b> {{flowName}}</p>
<p><b>Flow Hash:</b> {{flowHash}}</p>
<p><b>Param Hash:</b> {{paramHash}}</p>
<p><b>Combined Hash:</b> {{localHash}}</p>

<button class="bg-blue-600 text-white px-4 py-2 rounded hover:bg-blue-700">
Deploy
</button>

</div>

</div>

</template>
