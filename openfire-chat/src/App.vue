<template>
  <div class='bg-slate-100 min-h-screen p-6'><h1 class='text-3xl font-bold mb-4'>🚀 Openfire Chat Console</h1>
    <div class='grid grid-cols-[320px_220px_1fr] gap-4'>
      <SetupPanel :rooms='rooms' :participants='participants' @create='create' @add='addUser' @join='join'/>
      <ParticipantSidebar :participants='participants' @select='selected=$event'/>
      <ChatPanel :selected='selected' :messages='messages[selected]||[]' @send='send'/>
    </div>
  </div>
</template>
<script setup>import {ref, onMounted} from 'vue';
import SetupPanel from './components/SetupPanel.vue';
import ParticipantSidebar from './components/ParticipantSidebar.vue';
import ChatPanel from './components/ChatPanel.vue';
import * as api from './services/api';

const participants = ref([]), rooms = ref([]), selected = ref(''), messages = ref({});
const load = async () => {
  participants.value = (await api.getParticipants()).data;
  rooms.value = (await api.getRooms()).data;
  participants.value.forEach(x => {
    let n = x.participantName || x;
    if (!messages.value[n]) messages.value[n] = []
  });
  if (participants.value.length) selected.value = participants.value[0].participantName || participants.value[0]
};
const create = async (r) => {
  await api.createRoom(r);
  load()
};
const addUser = async (u) => {
  await api.addParticipant(u);
  load()
};
const join = async (x) => await api.addParticipantToRoom(x.room, x.user);
const send = async (m) => {
  await api.sendMessage(rooms.value[0]?.roomName || '', selected.value, m);
  messages.value[selected.value].push({message: m, mine: true})
};
onMounted(load)</script>
