<template>
  <div class="bg-white rounded-2xl shadow p-4">
    <h2 class="font-bold text-lg mb-4">
      Participants
    </h2>

    <div
        v-for="participant in participants"
        :key="participant"
        class="bg-slate-100 rounded-xl p-3 mb-2 flex items-center gap-2"
    >
      <div class="w-3 h-3 rounded-full bg-green-500"/>

      {{ participant }}

    </div>

  </div>
</template>

<script setup lang="ts">
defineProps<{
  participants:string[]
}>()
</script>
