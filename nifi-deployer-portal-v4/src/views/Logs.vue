
<script setup lang="ts">
import LogConsole from '../components/LogConsole.vue'

const logs=[
'Checking registry',
'Uploading version',
'Deploying flow',
'Deployment complete'
]
</script>

<template>

<h1 class="text-3xl font-bold mb-6">Logs</h1>

<LogConsole :logs="logs"/>

</template>
