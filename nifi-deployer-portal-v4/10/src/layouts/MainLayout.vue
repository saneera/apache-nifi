
<template>
<div style="display:flex;height:100vh">
<aside style="width:230px;background:#1e293b;color:white;padding:20px">
<h2>NiFi DevOps</h2>
<nav>
<p><router-link to="/">Dashboard</router-link></p>
<p><router-link to="/deploy">Deploy</router-link></p>
<p><router-link to="/registry">Registry</router-link></p>
<p><router-link to="/parameters">Parameters</router-link></p>
<p><router-link to="/history">History</router-link></p>
<p><router-link to="/logs">Logs</router-link></p>
</nav>
</aside>
<main style="flex:1;padding:20px;overflow:auto">
<router-view/>
</main>
</div>
</template>
