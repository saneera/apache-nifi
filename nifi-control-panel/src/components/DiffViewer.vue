<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'

const props = defineProps<{
  original: string
  modified: string
}>()

const el = ref<HTMLDivElement | null>(null)

let editor: any
let originalModel: any
let modifiedModel: any

onMounted(() => {
  if (!el.value) return

  editor = monaco.editor.createDiffEditor(el.value, {
    automaticLayout: true,
    theme: 'vs-dark'
  })

  originalModel = monaco.editor.createModel(props.original || '{}', 'json')
  modifiedModel = monaco.editor.createModel(props.modified || '{}', 'json')

  editor.setModel({
    original: originalModel,
    modified: modifiedModel
  })
})

watch(
    () => props.modified,
    (newVal) => {
      if (modifiedModel) {
        modifiedModel.setValue(newVal || '{}')
      }
    }
)

watch(
    () => props.original,
    (newVal) => {
      if (originalModel) {
        originalModel.setValue(newVal || '{}')
      }
    }
)
</script>

<template>
  <div ref="el" style="height: 500px;"></div>
</template>
