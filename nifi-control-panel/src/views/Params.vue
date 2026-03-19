<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { nifiService } from '../services/nifiService'

const contexts = ref<any[]>([])
const selected = ref<any | null>(null)

const loadContexts = async () => {
  contexts.value = await nifiService.parameterContexts()
}

const selectContext = async (ctx: any) => {
  selected.value = await nifiService.parameterContext(ctx.component.id)
}

onMounted(loadContexts)
</script>

<template>
  <div class="grid grid-cols-3 gap-4">

    <!-- LEFT: contexts -->
    <div class="bg-white p-4 rounded shadow">
      <h2 class="font-bold mb-3">Parameter Contexts</h2>

      <div
          v-for="c in contexts"
          :key="c.component.id"
          class="p-2 cursor-pointer hover:bg-gray-100 rounded"
          @click="selectContext(c)"
      >
        {{ c.component.name }}
      </div>
    </div>

    <!-- RIGHT: parameters -->
    <div class="col-span-2 bg-white p-4 rounded shadow">
      <h2 class="font-bold mb-3">
        {{ selected?.name || 'Select Context' }}
      </h2>

      <table v-if="selected" class="w-full text-sm">
        <thead>
        <tr class="text-left border-b">
          <th>Name</th>
          <th>Value</th>
          <th>Type</th>
        </tr>
        </thead>

        <tbody>
        <tr
            v-for="p in selected.parameters"
            :key="p.parameter.name"
            class="border-b"
        >
          <td class="py-2">{{ p.parameter.name }}</td>

          <td class="py-2">
              <span v-if="!p.parameter.sensitive">
                {{ p.parameter.value }}
              </span>
            <span v-else class="text-gray-400">
                ****** (sensitive)
              </span>
          </td>

          <td class="py-2">
            {{ p.parameter.sensitive ? 'Sensitive' : 'Normal' }}
          </td>
        </tr>
        </tbody>
      </table>

    </div>

  </div>
</template>
