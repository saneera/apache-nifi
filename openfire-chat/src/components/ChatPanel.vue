<template>
  <div class='chat'><h2>{{ selected }}</h2>
    <div style='height:420px;overflow:auto'>
      <div v-for='m in messages' :class="['msg',m.mine?'mine':'']">{{ m.message }}</div>
    </div>
    <div style='display:flex'><input v-model='txt'>
      <button @click='send'>Send</button>
    </div>
  </div>
</template>
<script setup>import {ref} from 'vue';

const txt = ref('');
const p = defineProps(['selected', 'messages']);
const e = defineEmits(['send']);
const send = () => {
  e('send', txt.value);
  txt.value = '';
}</script>
