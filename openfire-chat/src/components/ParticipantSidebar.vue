<template>
  <div class='sidebar'><h3>Participants</h3>
    <div class='user' v-for='p in participants' @click="$emit('select',p.participantName||p)">🟢
      {{ p.participantName || p }}
    </div>
  </div>
</template>
<script setup>defineProps(['participants'])</script>
