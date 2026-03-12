
<script setup lang="ts">
import LogConsole from "../components/LogConsole.vue"

const logs=[
"Authenticating",
"Fetching flows",
"Comparing hashes",
"Deployment ready"
]
</script>

<template>
<h2>Logs</h2>
<LogConsole :logs="logs"/>
</template>
