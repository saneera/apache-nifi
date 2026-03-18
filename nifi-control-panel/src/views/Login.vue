<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useNifiStore } from '../store/nifi'
import { useToast } from 'vue-toastification'

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

const router = useRouter()
const store = useNifiStore()
const toast = useToast()

const login = async () => {
  error.value = ''

  if (!username.value || !password.value) {
    error.value = 'Username and password required'
    return
  }

  try {
    loading.value = true

    await store.login(username.value, password.value)

    toast.success('Login successful')

    router.push('/')
  } catch (e: any) {
    const message =
        e.response?.data ||
        'Invalid username or password'

    error.value = message

    toast.error(message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex h-screen items-center justify-center">
    <div class="bg-white p-6 rounded shadow w-80 space-y-4">

      <h2 class="text-lg font-bold">Login</h2>

      <!-- ERROR MESSAGE -->
      <div
          v-if="error"
          class="bg-red-100 text-red-700 p-2 rounded text-sm"
      >
        {{ error }}
      </div>

      <input
          v-model="username"
          placeholder="Username"
          class="input"
      />

      <input
          v-model="password"
          type="password"
          placeholder="Password"
          class="input"
      />

      <button
          class="btn w-full flex items-center justify-center"
          @click="login"
          :disabled="loading"
      >
        <span v-if="loading">Logging in...</span>
        <span v-else>Login</span>
      </button>

    </div>
  </div>
</template>
