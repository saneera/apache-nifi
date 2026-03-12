
<script setup>
import { computed } from 'vue'
import jsondiffpatch from 'jsondiffpatch'
import { flowStore } from '../store/flowStore'

const diff = computed(()=>{
 if(!flowStore.raw) return null
 return jsondiffpatch.diff({},flowStore.raw)
})
</script>

<template>
<div>
<h3>Flow Diff</h3>
<pre>{{diff}}</pre>
</div>
</template>
