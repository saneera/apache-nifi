<template>
  <div class='space-y-4'>
    <div class='bg-white p-4 rounded-2xl shadow'><h3>Create Room</h3><input class='border p-2 w-full rounded mt-2'
                                                                            v-model='room'>
      <button class='bg-blue-600 text-white rounded px-4 py-2 mt-2' @click="$emit('create',room)">Create</button>
    </div>
    <div class='bg-white p-4 rounded-2xl shadow'><h3>Add Participant</h3><input class='border p-2 w-full rounded mt-2'
                                                                                v-model='user'>
      <button class='bg-green-600 text-white rounded px-4 py-2 mt-2' @click="$emit('add',user)">Add</button>
    </div>
    <div class='bg-white p-4 rounded-2xl shadow'><h3>Add Participant To Room</h3><select
        class='border p-2 w-full rounded mt-2' v-model='selectedRoom'>
      <option v-for='r in rooms' :value='r.roomName||r'>{{ r.roomName || r }}</option>
    </select><select class='border p-2 w-full rounded mt-2' v-model='selectedParticipant'>
      <option v-for='p in participants' :value='p.participantName||p'>{{ p.participantName || p }}</option>
    </select>
      <button class='bg-purple-600 text-white rounded px-4 py-2 mt-2 w-full'
              @click="$emit('join',{room:selectedRoom,user:selectedParticipant})">Join Room
      </button>
    </div>
  </div>
</template>
<script setup>
import {ref} from 'vue';

defineProps(['rooms', 'participants']);
const room = ref('');
const user = ref('');
const selectedRoom = ref('');
const selectedParticipant = ref('')
</script>
