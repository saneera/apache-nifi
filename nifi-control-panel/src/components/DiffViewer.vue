<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'

const props = defineProps<{
  original: string
  modified: string
}>()

const el = ref<HTMLDivElement | null>(null)

let editor: monaco.editor.IStandaloneDiffEditor | null = null

const createModels = () => {
  const old = editor?.getModel()
  old?.original?.dispose()
  old?.modified?.dispose()

  const originalModel = monaco.editor.createModel(props.original || '{}', 'json')
  const modifiedModel = monaco.editor.createModel(props.modified || '{}', 'json')

  editor?.setModel({
    original: originalModel,
    modified: modifiedModel
  })
}

onMounted(() => {
  if (!el.value) return

  editor = monaco.editor.createDiffEditor(el.value, {
    automaticLayout: true,
    theme: 'vs-dark'
  })

  createModels()
})

/**
 * 🔥 KEY FIX: recreate models when props change
 */
watch(
    () => [props.original, props.modified],
    () => {
      if (!editor) return

      createModels()
    }
)
</script>

<template>
  <div ref="el" style="height: 500px;"></div>
</template>
