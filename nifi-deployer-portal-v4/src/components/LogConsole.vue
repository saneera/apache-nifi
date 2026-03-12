
<script setup lang="ts">
defineProps({logs:Array})
</script>

<template>
<div style="background:#111;color:#0f0;padding:10px">
<div v-for="l in logs">{{l}}</div>
</div>
</template>
