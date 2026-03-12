<script setup lang="ts">

import { ref, watch } from "vue"
import { calculateDiff, formatDiff } from "../utils/diff"

const props = defineProps({
  localFlow: Object,
  registryFlow: Object
})

const diff = ref("")

watch(
    () => props.localFlow,
    () => {

      if(props.localFlow && props.registryFlow){

        const d = calculateDiff(
            props.localFlow,
            props.registryFlow
        )

        diff.value = formatDiff(d)

      }

    }
)

</script>

<template>

  <div class="bg-white shadow rounded p-4 mt-6">

    <h3 class="text-lg font-bold mb-3">
      Flow Differences
    </h3>

    <pre
        class="bg-black text-green-400 p-4 rounded overflow-auto text-sm"
    >

{{ diff || "No differences detected" }}

</pre>

  </div>

</template>
