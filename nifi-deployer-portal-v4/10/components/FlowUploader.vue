
<script setup lang="ts">

import { ref } from 'vue'
import { setFlow } from '../store/flowStore'

const name = ref("")

function upload(e:any){

 const file=e.target.files[0]
 const reader=new FileReader()

 reader.onload=()=>{

  const json=JSON.parse(reader.result as string)

  name.value=json.flowContents?.name || "Unknown Flow"

  setFlow(json.flowContents)

 }

 reader.readAsText(file)

}

</script>

<template>

<div class="bg-white shadow rounded p-6 max-w-xl">

<h3 class="font-semibold mb-4">Upload Flow JSON</h3>

<input type="file" @change="upload"/>

<p class="mt-4 font-bold" v-if="name">
Flow: {{name}}
</p>

</div>

</template>
