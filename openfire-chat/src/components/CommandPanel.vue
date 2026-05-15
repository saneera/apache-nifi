
<template>
<div class="card">
<h3>{{title}}</h3>
<textarea v-model="payload" rows="4"/>
<button @click="send">Execute</button>
<pre>{{result}}</pre>
</div>
</template>
<script setup lang="ts">
import {ref} from 'vue'
import api from '../api'
const props=defineProps<{title:string,endpoint:string,defaultPayload:string}>()
const payload=ref(props.defaultPayload)
const result=ref('')
const send=async()=>{
try{
 const r=await api.put(props.endpoint,JSON.parse(payload.value))
 result.value=JSON.stringify(r.data,null,2)
}catch(e:any){result.value='Error: '+e}
}
</script>
<style>.card{padding:15px;border:1px solid #ccc;border-radius:12px}textarea{width:100%}</style>
