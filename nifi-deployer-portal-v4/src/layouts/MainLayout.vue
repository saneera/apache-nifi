
<script setup lang="ts">
import { useAppStore } from "../store/appStore"
const store = useAppStore()
</script>

<template>
<div style="display:flex;height:100vh;font-family:Arial">
<aside style="width:240px;border-right:1px solid #ddd;padding:20px">
<h3>NiFi Portal</h3>

<select v-model="store.environment">
<option>DEV</option>
<option>TEST</option>
<option>PROD</option>
</select>

<ul style="list-style:none;padding:0;margin-top:20px">
<li><router-link to="/">Dashboard</router-link></li>
<li><router-link to="/deploy">Deploy Flow</router-link></li>
<li><router-link to="/registry">Registry</router-link></li>
<li><router-link to="/parameters">Parameters</router-link></li>
<li><router-link to="/logs">Logs</router-link></li>
</ul>
</aside>

<main style="flex:1;padding:20px">
<router-view/>
</main>
</div>
</template>
