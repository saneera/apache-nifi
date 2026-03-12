
<script setup lang="ts">
import { ref } from "vue"
import { calculateFlowHash,calculateParamHash,combinedHash } from "../utils/hash"

const flowName=ref("")
const flowHash=ref("")
const paramHash=ref("")
const localHash=ref("")

function upload(event:any){
const file=event.target.files[0]
const reader=new FileReader()

reader.onload=()=>{
const json=JSON.parse(reader.result as string)
flowName.value=json.flowContents.name
flowHash.value=calculateFlowHash(json.flowContents)
paramHash.value=calculateParamHash(json.parameterContexts)
localHash.value=combinedHash(flowHash.value,paramHash.value)
}

reader.readAsText(file)
}
</script>

<template>
<input type="file" @change="upload"/>
<div v-if="flowName">
<p>Flow: {{flowName}}</p>
<p>Flow Hash: {{flowHash}}</p>
<p>Param Hash: {{paramHash}}</p>
<p>Combined Hash: {{localHash}}</p>
<button>Deploy</button>
</div>
</template>
