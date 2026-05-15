<template>

  <div class="space-y-5">

    <div class="bg-white p-5 rounded-2xl shadow">
      <h3 class="font-semibold mb-3">
        Create Room
      </h3>

      <input
          v-model="room"
          class="border rounded-lg p-2 w-full"
      />

      <button
          class="bg-blue-600 text-white rounded-lg px-5 py-2 mt-3"
          @click="$emit('create',room)"
      >
        Create
      </button>

    </div>


    <div class="bg-white p-5 rounded-2xl shadow">

      <h3 class="font-semibold mb-3">
        Add Participant
      </h3>

      <input
          v-model="user"
          class="border rounded-lg p-2 w-full"
      />

      <button
          class="bg-green-600 text-white rounded-lg px-5 py-2 mt-3"
          @click="$emit('add',user)"
      >
        Add
      </button>

    </div>


    <div class="bg-white p-5 rounded-2xl shadow">

      <h3 class="font-semibold mb-3">
        Add Participant To Room
      </h3>

      <select
          v-model="selectedRoom"
          class="border rounded-lg p-2 w-full mb-3"
      >
        <option disabled value="">
          Select room
        </option>

        <option
            v-for="room in rooms"
            :key="room.roomName"
            :value="room.roomName"
        >
          {{ room.roomName }}
        </option>

      </select>


      <select
          v-model="selectedParticipant"
          class="border rounded-lg p-2 w-full"
      >

        <option disabled value="">
          Select participant
        </option>

        <option
            v-for="participant in participants"
            :key="participant"
            :value="participant"
        >
          {{ participant }}
        </option>

      </select>


      <button
          class="w-full mt-4 bg-purple-600 text-white rounded-lg py-3"
          @click="joinRoom"
      >

        Join Room

      </button>

    </div>

  </div>

</template>


<script setup lang="ts">

import {ref} from 'vue'

defineProps({
  rooms:Array,
  participants:Array
})

const room=ref('')
const user=ref('')

const selectedRoom=ref('')
const selectedParticipant=ref('')

const emit=defineEmits([
  'create',
  'add',
  'join'
])

function joinRoom(){

  emit(
      'join',
      {
        room:selectedRoom.value,
        user:selectedParticipant.value
      }
  )

}

</script>
