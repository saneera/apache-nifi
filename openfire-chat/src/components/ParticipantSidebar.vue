<template>
  <div class='bg-white p-4 rounded-2xl shadow'><h3 class='font-bold'>Participants</h3>
    <div v-for='p in participants' @click="$emit('select',p.participantName||p)"
         class='p-3 bg-slate-100 rounded-xl my-2 cursor-pointer'>🟢 {{ p.participantName || p }}
    </div>
  </div>
</template>
<script setup>
defineProps(['participants'])
</script>
