
<script setup>
import { ref } from 'vue'
import { flowStore } from '../store/flowStore'
import { deployFlow } from '../services/deployService'

const name = ref("")
const status = ref("")

function upload(e){
 const file = e.target.files[0]
 const reader = new FileReader()
 reader.onload = ()=>{
  const json = JSON.parse(reader.result)
  name.value = json.flowContents.name
  flowStore.flow = json.flowContents
  flowStore.raw = json
 }
 reader.readAsText(file)
}

async function deploy(){
 status.value = "Starting deployment..."
 await deployFlow(flowStore.raw,status)
}
</script>

<template>
<div>
<input type="file" @change="upload"/>
<p v-if="name">Flow: {{name}}</p>
<button @click="deploy">Deploy</button>
<p>{{status}}</p>
</div>
</template>
