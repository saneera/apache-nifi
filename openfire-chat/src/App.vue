
<template>
<div :class="dark?'dark':'light'" class="page">
<div class="header">
<h1>🚀 Openfire Chat Console</h1>
<button @click="dark=!dark">🌙 Theme</button>
</div>

<div class="container">
<div class="setup">
<div class="card">
<h3>Create Room</h3>
<input v-model="room"/>
<button @click="createRoom">Create</button>
</div>

<div class="card">
<h3>Add Participant</h3>
<input v-model="user"/>
<button @click="addUser">Add</button>
</div>

<div class="card">
<h3>Add User To Room</h3>
<input v-model="joinUser"/>
<button @click="joinRoom">Join</button>
</div>

<div class="card">
<h3>Logs</h3>
<div class="log" v-for="l in logs">{{l}}</div>
</div>
</div>

<div class="chatWrap">
<div class="sidebar">
<div v-for="p in participants"
class="user"
@click="selected=p">
🟢 {{p}}
</div>
</div>

<div class="chat">
<div class="title">{{selected}}</div>
<div class="msgs">
<div v-for="m in messages[selected]"
:class="['msg',m.mine?'mine':'']">
{{m.message}}
</div>
</div>

<div class="send">
<input v-model="txt" placeholder="type message"/>
<button @click="send">Send</button>
</div>
</div>
</div>
</div>
</div>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import api from './api'

const dark=ref(true)
const room=ref('TestMessage')
const user=ref('participant1')
const joinUser=ref('participant1')
const txt=ref('')
const selected=ref('participant1')
const participants=ref(['participant1','participant2','participant3'])
const logs=ref([])

const messages=ref({
participant1:[],
participant2:[],
participant3:[]
})

const addLog=(m)=>logs.value.unshift(m)

const createRoom=async()=>{
await api.put('/create-room',{roomName:room.value})
addLog('Room created '+room.value)
}

const addUser=async()=>{
await api.put('/add-participant',{participantName:user.value})
if(!participants.value.includes(user.value))
participants.value.push(user.value)
addLog('Participant added '+user.value)
}

const joinRoom=async()=>{
await api.put('/add-participant-to-room',{
roomName:room.value,
participantName:joinUser.value
})
addLog(joinUser.value+' joined '+room.value)
}

const send=async()=>{
await api.put('/send-message',{
roomName:room.value,
participantName:selected.value,
message:txt.value
})

messages.value[selected.value].push({
message:txt.value,mine:true
})

addLog(selected.value+' sent '+txt.value)
txt.value=''
}
</script>

<style>
body{margin:0;font-family:Arial}
.page{min-height:100vh;padding:20px}
.dark{background:#0f172a;color:white}
.light{background:#f5f5f5;color:black}
.header{display:flex;justify-content:space-between}
.container{display:flex;gap:20px}
.setup{width:320px}
.card{background:#1e293b;padding:15px;border-radius:16px;margin-bottom:15px}
input{width:100%;padding:10px;margin-top:8px;margin-bottom:8px;border-radius:8px;border:none}
button{padding:10px;border:none;border-radius:8px;cursor:pointer}
.chatWrap{display:flex;flex:1;gap:15px}
.sidebar{width:220px;background:#1e293b;padding:15px;border-radius:16px}
.user{padding:10px;background:#334155;border-radius:10px;margin:8px;cursor:pointer}
.chat{flex:1;background:#1e293b;border-radius:16px;padding:15px}
.title{font-size:22px;margin-bottom:15px}
.msgs{height:450px;overflow:auto}
.msg{background:#475569;padding:12px;border-radius:12px;width:max-content;margin:8px}
.mine{margin-left:auto;background:#2563eb}
.send{display:flex;gap:10px}
.send input{flex:1}
.log{font-size:12px;padding:4px}
</style>
