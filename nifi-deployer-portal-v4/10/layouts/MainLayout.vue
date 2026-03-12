
<template>
<div class="flex h-screen">

<aside class="w-72 bg-slate-900 text-white p-6">
<h1 class="text-2xl font-bold mb-6">NiFi DevOps Portal</h1>

<nav class="space-y-4">
<router-link to="/" class="block hover:text-blue-400">Dashboard</router-link>
<router-link to="/deploy" class="block hover:text-blue-400">Deploy Flow</router-link>
</nav>

</aside>

<main class="flex-1 p-8 overflow-auto">
<router-view/>
</main>

</div>
</template>
