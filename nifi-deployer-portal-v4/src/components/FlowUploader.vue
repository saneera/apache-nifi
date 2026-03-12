<script setup lang="ts">

import { ref } from "vue"
import { uploadRegistryVersion } from "../api/deployApi"

const emit = defineEmits(["flow-loaded","deploy"])

const flowName=ref("")
const flowHash=ref("")
const paramHash=ref("")
const localHash=ref("")
const flowJson=ref(null)

function upload(event:any){

  const file=event.target.files[0]

  const reader=new FileReader()

  reader.onload=()=>{

    const json=JSON.parse(reader.result as string)

    flowJson.value = json

    emit("flow-loaded", json)

    flowName.value=json.flowContents.name

  }

  reader.readAsText(file)

}

function deploy(){

  emit("deploy", flowJson.value)

}

</script>

<template>

  <div class="bg-white p-6 rounded shadow max-w-xl">

    <input type="file" @change="upload" class="mb-4"/>

    <div v-if="flowName">

      <p><b>Flow:</b> {{flowName}}</p>

      <button
          class="bg-blue-600 text-white px-4 py-2 rounded hover:bg-blue-700 mt-4"
          @click="deploy"
      >
        Deploy
      </button>

    </div>

  </div>

</template>
