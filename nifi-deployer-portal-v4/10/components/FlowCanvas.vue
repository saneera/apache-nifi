
<script setup lang="ts">

import { VueFlow, useVueFlow } from '@vue-flow/core'
import { computed } from 'vue'
import { flowState } from '../store/flowStore'

const elements = computed(()=>{

 if(!flowState.flow) return []

 const nodes = (flowState.flow.processors || []).map(p=>({
  id:p.identifier,
  label:p.name,
  position:p.position || {x:0,y:0}
 }))

 const edges = (flowState.flow.connections || []).map(c=>({
  id:c.identifier,
  source:c.source?.id,
  target:c.destination?.id
 }))

 return [...nodes,...edges]

})

</script>

<template>

<div class="bg-white shadow rounded p-4 mt-6">

<h3 class="font-semibold mb-4">NiFi Flow Canvas</h3>

<div style="height:500px">

<VueFlow :elements="elements" fit-view />

</div>

</div>

</template>
