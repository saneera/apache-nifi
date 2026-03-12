
<script setup>
import { ref,onMounted } from 'vue'
import { getParameterContexts, updateParameterContext } from '../api/nifiApi'

const contexts=ref([])
const selected=ref(null)

onMounted(async()=>{
 contexts.value = await getParameterContexts()
})

async function save(){
 await updateParameterContext(selected.value.component.id, selected.value)
 alert("Updated")
}
</script>

<template>
<h1>Parameter Contexts</h1>

<ul>
<li v-for="c in contexts" @click="selected=c">
{{c.component.name}}
</li>
</ul>

<div v-if="selected">
<h3>Edit {{selected.component.name}}</h3>
<textarea v-model="selected.component.parameters"></textarea>
<button @click="save">Save</button>
</div>

</template>
