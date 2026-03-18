<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'

const props = defineProps<{ original: string, modified: string }>()
const el = ref<HTMLDivElement | null>(null)
let editor: any

onMounted(() => {
  if (!el.value) return

  const originalModel = monaco.editor.createModel(props.original || '{}', 'json')
  const modifiedModel = monaco.editor.createModel(props.modified || '{}', 'json')

  editor = monaco.editor.createDiffEditor(el.value, {
    automaticLayout: true,
    theme: 'vs-dark'
  })

  editor.setModel({
    original: originalModel,
    modified: modifiedModel
  })
})

watch(() => [props.original, props.modified], ([o, m]) => {
  if (!editor) return
  const model = editor.getModel()
  model.original.setValue(o || '{}')
  model.modified.setValue(m || '{}')
})
</script>

<template>
  <div ref="el" style="height: 500px; border: 1px solid #e5e7eb"></div>
</template>
