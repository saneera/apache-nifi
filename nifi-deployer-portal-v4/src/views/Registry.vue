
<script setup lang="ts">
import { ref,onMounted } from "vue"
import { getRegistryFlows } from "../api/registryApi"

const flows = ref([])

onMounted(async()=>{
 flows.value = await getRegistryFlows()
})
</script>

<template>
<h2>Registry Browser</h2>
<ul>
<li v-for="f in flows" :key="f.identifier">
{{f.name}} ({{f.identifier}})
</li>
</ul>
</template>
