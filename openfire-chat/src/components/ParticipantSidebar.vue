<template>
  <div class='bg-white rounded-2xl shadow p-4'><h3 class='font-bold mb-4'>Participants</h3>
    <div v-for='p in participants' class='p-3 rounded-xl bg-slate-100 mb-2 cursor-pointer hover:bg-blue-100'
         @click="$emit('select',p.participantName||p)">🟢 {{ p.participantName || p }}
    </div>
  </div>
</template>
<script setup>
defineProps(['participants'])
</script>
