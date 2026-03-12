<script setup lang="ts">

import { ref } from "vue"
import FlowUploader from "../components/FlowUploader.vue"
import FlowDiffViewer from "../components/FlowDiffViewer.vue"

const localFlow = ref(null)
const registryFlow = ref(null)

</script>

<template>

  <h1 class="text-2xl font-bold mb-6">Deploy Flow</h1>

  <FlowUploader @flow-loaded="localFlow = $event" />

  <FlowDiffViewer
      :localFlow="localFlow"
      :registryFlow="registryFlow"
  />

</template>
