<script setup lang="ts">

import { ref, watch } from "vue"
import { calculateDiff } from "../utils/diff"

const props = defineProps({
  localFlow: Object,
  registryFlow: Object
})

const diff = ref(null)

watch(
    () => props.localFlow,
    () => {

      if(props.localFlow && props.registryFlow){

        diff.value = calculateDiff(
            props.localFlow,
            props.registryFlow
        )

      }

    },
    { immediate: true }
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

{{ diff ? JSON.stringify(diff,null,2) : "No diff available yet" }}

</pre>

  </div>

</template>
