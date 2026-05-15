
<template><div class="sidebar">
<div v-for="p in participants" :key="p" class="item" @click="$emit('select',p)">🟢 {{p}}</div>
</div></template>
<script setup lang="ts">
defineProps<{participants:string[]}>()
</script>
<style>
.sidebar{width:250px;background:#1e293b;padding:10px;border-radius:12px}
.item{padding:10px;margin:5px;background:#334155;border-radius:8px;cursor:pointer;color:white}
</style>
