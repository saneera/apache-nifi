
<script setup lang="ts">
import { useAppStore } from "../store/appStore"
const store = useAppStore()
</script>

<template>

<div class="flex h-screen">

<aside class="w-72 bg-slate-900 text-white p-6">

<h1 class="text-2xl font-bold mb-6">
NiFi CI/CD Portal
</h1>

<select v-model="store.environment"
class="w-full p-2 text-black rounded mb-6">

<option>DEV</option>
<option>TEST</option>
<option>PROD</option>

</select>

<nav class="space-y-4">

<router-link class="block hover:text-blue-400" to="/">Dashboard</router-link>
<router-link class="block hover:text-blue-400" to="/deploy">Deploy Flow</router-link>
<router-link class="block hover:text-blue-400" to="/registry">Registry</router-link>
<router-link class="block hover:text-blue-400" to="/history">Deploy History</router-link>
<router-link class="block hover:text-blue-400" to="/logs">Logs</router-link>

</nav>

</aside>

<main class="flex-1 p-10 overflow-auto">

<router-view/>

</main>

</div>

</template>
