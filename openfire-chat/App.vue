
<template>
<div :class="dark?'dark':'light'" class="page">
<h1>Openfire Chat Client</h1>
<button @click="dark=!dark">Toggle Theme</button>
<div class="layout">
<ParticipantSidebar :participants="participants" @select="selected=$event"/>
<ChatPanel :participant="selected" :messages="messages[selected]" @send="sendMessage"/>
</div>
</div>
</template>
<script setup lang="ts">
import {ref} from 'vue'
import api from './api'
import ParticipantSidebar from './components/ParticipantSidebar.vue'
import ChatPanel from './components/ChatPanel.vue'

const dark=ref(true)
const participants=['participant1','participant2','participant3']
const selected=ref('participant1')

const messages=ref({
participant1:[],
participant2:[],
participant3:[]
})

const sendMessage=async(data:any)=>{
 await api.put('/send-message',{
   roomName:'TestMessage',
   participantName:data.participant,
   message:data.message
 })
 messages.value[data.participant].push({
   message:data.message,
   mine:true
 })
}
</script>
<style>
.page{padding:20px;min-height:100vh}
.dark{background:#0f172a;color:white}
.light{background:#f3f4f6;color:black}
.layout{display:flex;gap:20px}
button{padding:10px;margin-bottom:15px}
</style>
