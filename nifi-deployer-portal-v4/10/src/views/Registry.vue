
<script setup>
import { ref,onMounted } from 'vue'
import { getFlows,getVersions } from '../api/registryApi'

const flows=ref([])
const versions=ref([])

async function loadVersions(id){
 versions.value = await getVersions(id)
}

onMounted(async()=>{
 flows.value = await getFlows()
})
</script>

<template>
<h1>Registry</h1>
<ul>
<li v-for="f in flows">
{{f.name}}
<button @click="loadVersions(f.identifier)">versions</button>
</li>
</ul>

<h3>Versions</h3>
<ul>
<li v-for="v in versions">
v{{v.snapshotMetadata.version}}
</li>
</ul>
</template>
