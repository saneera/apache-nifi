<template>
  <div class='bg-white p-4 rounded-2xl shadow'><h2 class='font-bold mb-3'>{{ selected }}</h2>
    <div class='h-96 overflow-auto'>
      <div v-for='m in messages' :class="m.mine?'ml-auto bg-blue-500 text-white':'bg-slate-200'"
           class='p-3 rounded-2xl mb-2 w-fit'>{{ m.message }}
      </div>
    </div>
    <div class='flex gap-2 mt-2'><input class='border rounded p-2 flex-1' v-model='txt'>
      <button class='bg-blue-600 text-white px-4 rounded' @click='s'>Send</button>
    </div>
  </div>
</template>
<script setup>
import {ref} from 'vue';

const txt = ref('');
const p = defineProps(['selected', 'messages']);
const e = defineEmits(['send']);
const s = () => {
  e('send', txt.value);
  txt.value = '';
}
</script>
