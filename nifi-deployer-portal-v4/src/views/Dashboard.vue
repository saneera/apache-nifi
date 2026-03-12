
<script setup lang="ts">
import { ref,onMounted } from "vue"
import { login,getRootFlows } from "../api/nifiApi"

const flows = ref([])

onMounted(async()=>{
 await login()
 flows.value = await getRootFlows()
})
</script>

<template>
<h2>Dashboard</h2>
<table border="1" cellpadding="6">
<tr>
<th>Name</th>
<th>ID</th>
<th>Position</th>
</tr>
<tr v-for="f in flows" :key="f.id">
<td>{{f.component.name}}</td>
<td>{{f.id}}</td>
<td>{{f.component.position.x}},{{f.component.position.y}}</td>
</tr>
</table>
</template>
