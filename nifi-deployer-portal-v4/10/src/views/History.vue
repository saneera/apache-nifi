
<script setup>
import { ref,onMounted } from 'vue'

const history = ref([])

onMounted(()=>{
 history.value = JSON.parse(localStorage.getItem("deployHistory") || "[]")
})
</script>

<template>
<h1>Deployment History</h1>
<ul>
<li v-for="h in history">
{{h.flow}} - {{h.time}}
</li>
</ul>
</template>
