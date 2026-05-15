<template>
  <div class="bg-white rounded-2xl shadow p-4">
    <h2 class="font-bold text-lg mb-4">
      Participants
    </h2>

    <div
        v-for="participant in participants"
        :key="participant"
        @click="$emit('select', participant)"
        class="bg-slate-100 hover:bg-blue-50 cursor-pointer rounded-xl p-3 mb-2 flex items-center gap-3"
    >
      <div class="w-3 h-3 rounded-full bg-green-500"></div>

      <span>
        {{ participant }}
      </span>
    </div>

  </div>
</template>

<script setup lang="ts">
defineProps<{
  participants:string[]
}>()
</script>
