
<script setup>
import { computed } from 'vue'
import { VueFlow } from '@vue-flow/core'
import { flowStore } from '../store/flowStore'

const elements = computed(()=>{
 if(!flowStore.flow) return []

 const nodes = (flowStore.flow.processors||[]).map(p=>({
  id:p.identifier,
  label:p.name,
  position:p.position
 }))

 const edges = (flowStore.flow.connections||[]).map(c=>({
  id:c.identifier,
  source:c.source.id,
  target:c.destination.id
 }))

 return [...nodes,...edges]
})
</script>

<template>
<div style="height:400px;border:1px solid #ccc">
<VueFlow :elements="elements" fit-view />
</div>
</template>
