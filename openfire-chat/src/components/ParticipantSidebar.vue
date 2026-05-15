<template>
  <div class="bg-white rounded-2xl shadow h-full flex flex-col">

    <div class="p-5 border-b">
      <h2 class="font-bold text-xl">
        Participants
      </h2>

      <p class="text-sm text-gray-500">
        {{ participants.length }} online
      </p>
    </div>

    <div class="overflow-y-auto flex-1 p-3">

      <div
          v-for="participant in participants"
          :key="participant"
          @click="$emit('select',participant)"
          :class="[
            selected===participant
            ?'bg-blue-100 border-blue-400'
            :'hover:bg-slate-100',
            'cursor-pointer rounded-xl p-3 mb-2 border transition'
        ]"
      >

        <div class="flex items-center gap-3">

          <div class="relative">

            <div
                class="w-11 h-11 rounded-full bg-indigo-500 text-white flex items-center justify-center font-bold"
            >
              {{ participant.charAt(0).toUpperCase() }}
            </div>

            <div
                class="absolute bottom-0 right-0 w-3 h-3 bg-green-500 border-2 border-white rounded-full"
            />

          </div>

          <div>

            <div class="font-medium">
              {{ participant }}
            </div>

            <div class="text-xs text-gray-500">
              Online
            </div>

          </div>

        </div>

      </div>

    </div>

  </div>
</template>

<script setup lang="ts">

defineProps<{
  participants:string[]
  selected:string
}>()

defineEmits([
  'select'
])

</script>
