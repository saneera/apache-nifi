
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

<h1 class="text-2xl font-bold mb-6">Dashboard</h1>

<table class="w-full bg-white shadow rounded">

<thead class="bg-gray-200">
<tr>
<th class="p-3 text-left">Flow</th>
<th class="p-3 text-left">ID</th>
<th class="p-3 text-left">Position</th>
</tr>
</thead>

<tbody>

<tr v-for="f in flows" :key="f.id" class="border-t hover:bg-gray-50">

<td class="p-3">{{f.component.name}}</td>

<td class="p-3 text-xs text-gray-500">{{f.id}}</td>

<td class="p-3">
{{f.component.position.x}}, {{f.component.position.y}}
</td>

</tr>

</tbody>

</table>

</template>
