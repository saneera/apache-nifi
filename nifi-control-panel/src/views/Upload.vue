<script setup lang="ts">
import { ref } from 'vue'
import DiffViewer from '../components/DiffViewer.vue'

const file = ref<File|null>(null)
const localJson = ref('{}')
const registryJson = ref('{}')

const onFile = async (e: any) => {
  file.value = e.target.files[0]
  localJson.value = await file.value.text()
}

function simpleHash(s: string) {
  let h = 0, i = 0, len = s.length
  while (i < len) { h = (h << 5) - h + s.charCodeAt(i++) | 0 }
  return String(h)
}
</script>

<template>
  <div>
    <h2 class="text-xl mb-4 font-semibold">Upload + Diff</h2>
    <input type="file" @change="onFile" class="mb-4"/>
    <div class="grid grid-cols-2 gap-4 mb-2 text-sm">
      <div>Local Hash: {{ simpleHash(localJson) }}</div>
      <div>Registry Hash: {{ simpleHash(registryJson) }}</div>
    </div>
    <DiffViewer :original="registryJson" :modified="localJson"/>
  </div>
</template>
