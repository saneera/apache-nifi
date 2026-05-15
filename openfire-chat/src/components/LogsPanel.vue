<template>
  <div class='card'><h3>Logs</h3>
    <div v-for='l in logs'>{{ l }}</div>
  </div>
</template>
<script setup>
defineProps(['logs'])
</script>
