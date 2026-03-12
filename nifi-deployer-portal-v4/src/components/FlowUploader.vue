
<script setup lang="ts">
import { ref } from 'vue'

const flowName = ref('')

function upload(event:any){

const file = event.target.files[0]

const reader = new FileReader()

reader.onload = ()=>{

const json = JSON.parse(reader.result as string)

flowName.value = json.flowContents.name

}

reader.readAsText(file)

}

</script>

<template>

<div class="bg-white shadow rounded p-6 max-w-xl">

<h3 class="font-semibold mb-4">Upload Flow JSON</h3>

<input type="file" @change="upload" class="mb-4"/>

<p v-if="flowName" class="font-bold">
Flow: {{flowName}}
</p>

<button
class="bg-blue-600 text-white px-5 py-2 rounded mt-4 hover:bg-blue-700"
>
Deploy Flow
</button>

</div>

</template>
