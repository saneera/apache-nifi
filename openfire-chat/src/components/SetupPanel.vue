<template>
  <div class='space-y-4'>
    <div class='bg-white rounded-2xl shadow p-4'><h3>Create Room</h3>
      <input v-model='room' class='border p-2 w-full rounded'>
      <button class='bg-blue-600 text-white px-4 py-2 rounded mt-2' @click='$emit(`create`,room)'>Create</button>
    </div>
    <div class='bg-white rounded-2xl shadow p-4'><h3>Add Participant</h3>
      <input v-model='user' class='border p-2 w-full rounded'>
      <button class='bg-green-600 text-white px-4 py-2 rounded mt-2' @click='$emit(`add`,user)'>Add</button>
    </div>
  </div>
</template>
  <script setup>
    import {ref} from 'vue';

    const room = ref('');
    const user = ref('')
  </script>
