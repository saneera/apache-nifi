<script setup lang="ts">
import { ref } from 'vue'
import DiffViewer from '../components/DiffViewer.vue'

import { normalizeFlow } from '../utils/normalizeFlow'
import {nifiService} from "../services/nifiService";

const file = ref<File|null>(null)
const localJson = ref('{}')
const registryJson = ref('{}')


const onFile = async (e: any) => {
  const file = e.target.files[0]
  if (!file) return

  const text = await file.text()

  try {
    const parsed = JSON.parse(text)

    const normalized = normalizeFlow(parsed)

    localJson.value = JSON.stringify(normalized, null, 2)

    // 🔥 extract flow name
    const flowName =
        parsed.flowContents?.name ||
        parsed.header?.flowName

// 🔥 load registry version
    await loadRegistryFlow(flowName)
  } catch {
    localJson.value = text
  }
}

const loadRegistryFlow = async (flowName: string) => {
  const flows = await nifiService.registryFlows()

  const match = flows.find((f: any) => f.name === flowName)

  if (!match) {
    console.warn('Flow not found in registry')
    return
  }

  const snapshot = await nifiService.registryFlow(match.identifier)

  const normalized = normalizeFlow(snapshot)

  registryJson.value = JSON.stringify(normalized, null, 2)
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
