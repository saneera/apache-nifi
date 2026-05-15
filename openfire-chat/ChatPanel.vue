
<template>
<div class="chat">
<h2>{{participant}}</h2>
<div class="messages">
<div v-for="m in messages" class="msg" :class="{mine:m.mine}">{{m.message}}</div>
</div>
<div class="bottom">
<input v-model="text" placeholder="message"/>
<button @click="send">Send</button>
</div>
</div>
</template>
<script setup lang="ts">
import {ref} from 'vue'
const text=ref('')
const props=defineProps<{participant:string,messages:any[]}>()
const emit=defineEmits(['send'])
const send=()=>{
 if(!text.value)return
 emit('send',{participant:props.participant,message:text.value})
 text.value=''
}
</script>
<style>
.chat{flex:1;padding:20px;background:#1e293b;border-radius:12px;color:white}
.messages{height:400px;overflow:auto}
.msg{padding:10px;margin:8px;background:#475569;border-radius:10px;width:max-content}
.mine{margin-left:auto;background:#2563eb}
.bottom{display:flex;gap:10px}
input{flex:1;padding:12px}
</style>
