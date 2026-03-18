<script setup lang="ts">
import { useNifiStore } from './store/nifi'
import { computed } from 'vue'
import Loader from './components/Loader.vue'

const store = useNifiStore()
const isLoggedIn = computed(() => !!store.token)

const logout = () => {
  store.logout()
  window.location.href = '/login'
}
</script>

<template>
  <Loader />
  <div class="h-screen flex flex-col">

    <!-- ✅ TOP BAR -->
    <header
        v-if="isLoggedIn"
        class="h-14 bg-white shadow flex items-center justify-between px-6"
    >
      <div class="font-semibold text-lg">
        NiFi Control Plane
      </div>

      <div class="flex items-center gap-4">
        <span class="text-sm text-gray-600">
          Logged In
        </span>

        <button
            class="bg-red-500 hover:bg-red-600 text-white px-3 py-1 rounded"
            @click="logout"
        >
          Logout
        </button>
      </div>
    </header>

    <!-- ✅ BODY -->
    <div class="flex flex-1">

      <!-- SIDEBAR -->
      <aside
          v-if="isLoggedIn"
          class="w-64 bg-gray-900 text-white p-4 space-y-2"
      >
        <div class="font-bold text-lg mb-4">Menu</div>

        <router-link to="/" class="nav">Dashboard</router-link>
        <router-link to="/registry" class="nav">Registry</router-link>
        <router-link to="/upload" class="nav">Upload + Diff</router-link>
        <router-link to="/params" class="nav">Parameters</router-link>
      </aside>

      <!-- MAIN CONTENT -->
      <main
          :class="[
          'p-6 bg-gray-100 overflow-auto',
          isLoggedIn ? 'flex-1' : 'w-full'
        ]"
      >
        <router-view />
      </main>

    </div>
  </div>
</template>

<style>
.nav {
  @apply block px-3 py-2 rounded hover:bg-gray-700;
}
</style>
