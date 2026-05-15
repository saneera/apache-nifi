<template>
  <div :class="dark?'dark':'light'" class="page">
    <HeaderBar @toggle="dark=!dark"/>
    <div class="layout">
      <div>
        <SetupPanel :rooms="rooms" :participants="participants" @create="create" @add="addUser" @join="join"/>
        <LogsPanel :logs="logs"/>
      </div>
      <ParticipantSidebar :participants="participants" @select="selected=$event"/>
      <ChatPanel :selected="selected" :messages="messages[selected]||[]" @send="send"/>
    </div>
  </div>
</template>
<script setup>
import {ref, onMounted} from 'vue'
import HeaderBar from './components/HeaderBar.vue'
import SetupPanel from './components/SetupPanel.vue'
import ParticipantSidebar from './components/ParticipantSidebar.vue'
import LogsPanel from './components/LogsPanel.vue'
import ChatPanel from './components/ChatPanel.vue'
import * as api from './services/api'

const dark = ref(true), rooms = ref([]), participants = ref([]), logs = ref([]), selected = ref(''), messages = ref({})
const load = async () => {
  participants.value = (await api.getParticipants()).data;
  rooms.value = (await api.getRooms()).data;
  participants.value.forEach(x => {
    let n = x.participantName || x;
    if (!messages.value[n]) messages.value[n] = []
  });
  if (participants.value.length) selected.value = participants.value[0].participantName || participants.value[0]
}
const create = async (r) => {
  await api.createRoom(r);
  logs.value.unshift('Created ' + r);
  load()
}
const addUser = async (u) => {
  await api.addParticipant(u);
  logs.value.unshift('Added ' + u);
  load()
}
const join = async (x) => {
  await api.addParticipantToRoom(x.room, x.user);
  logs.value.unshift(x.user + ' joined ' + x.room)
}
const send = async (m) => {
  await api.sendMessage(rooms.value[0]?.roomName || rooms.value[0], selected.value, m);
  messages.value[selected.value].push({message: m, mine: true})
}
onMounted(load)
</script>
