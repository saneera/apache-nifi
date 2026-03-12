
<script setup lang="ts">
import { useAppStore } from "../store/appStore"
const store = useAppStore()
</script>

<template>
<div class="flex h-screen">
<aside class="w-64 bg-gray-900 text-white p-6">
<h2 class="text-xl font-bold mb-6">NiFi Portal</h2>

<select v-model="store.environment" class="w-full text-black p-2 rounded mb-6">
<option>DEV</option>
<option>TEST</option>
<option>PROD</option>
</select>

<nav class="space-y-3">
<router-link class="block hover:text-blue-400" to="/">Dashboard</router-link>
<router-link class="block hover:text-blue-400" to="/deploy">Deploy Flow</router-link>
<router-link class="block hover:text-blue-400" to="/registry">Registry</router-link>
<router-link class="block hover:text-blue-400" to="/parameters">Parameters</router-link>
<router-link class="block hover:text-blue-400" to="/logs">Logs</router-link>
</nav>
</aside>

<main class="flex-1 p-8 overflow-auto bg-gray-100">
<router-view/>
</main>
</div>
</template>
