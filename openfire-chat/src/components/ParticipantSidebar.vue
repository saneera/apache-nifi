<template>
  <div class="bg-white rounded-2xl shadow p-4">
    <h2 class="font-bold text-lg mb-4">
      Participants
    </h2>

    <div
        v-for="participant in participants"
        :key="participant"
    >
      {{ participant }}
    </div>

  </div>
</template>

<script setup lang="ts">
defineProps<{
  participants:string[]
}>()
</script>
