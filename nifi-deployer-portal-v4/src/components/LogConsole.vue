
<script setup lang="ts">
defineProps({logs:Array})
</script>

<template>

<div class="bg-black text-green-400 p-4 rounded font-mono text-sm">

<div v-for="l in logs">{{l}}</div>

</div>

</template>
