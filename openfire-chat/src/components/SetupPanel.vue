<template>
  <div>
    <div class='card'><h3>Create Room</h3><input v-model='room'>
      <button @click="$emit(`create`,room)">Create</button>
    </div>
    <div class='card'><h3>Add Participant</h3><input v-model='user'>
      <button @click="$emit(`add`,user)">Add</button>
    </div>
    <div class='card'><h3>Join Room</h3><select v-model='r'>
      <option v-for='x in rooms'>{{ x.roomName || x }}</option>
    </select><select v-model='u'>
      <option v-for='x in participants'>{{ x.participantName || x }}</option>
    </select>
      <button @click="$emit(`join`,{room:r,user:u})">Join</button>
    </div>
  </div>
</template>
<script setup>import {ref} from 'vue';

defineProps(['rooms', 'participants']);
const room = ref('');
const user = ref('');
const r = ref('');
const u = ref('')</script>
