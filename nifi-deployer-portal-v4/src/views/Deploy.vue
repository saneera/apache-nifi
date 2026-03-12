
<script setup lang="ts">
import FlowUploader from '../components/FlowUploader.vue'
import FlowDiffViewer from '../components/FlowDiffViewer.vue'
import DeployProgress from '../components/DeployProgress.vue'
</script>

<template>

<h1 class="text-3xl font-bold mb-6">Deploy Flow</h1>

<FlowUploader/>

<FlowDiffViewer/>

<DeployProgress/>

</template>
