
import { createRouter, createWebHistory } from "vue-router"

import Dashboard from "../views/Dashboard.vue"
import Deploy from "../views/Deploy.vue"
import Registry from "../views/Registry.vue"
import Parameters from "../views/Parameters.vue"
import Logs from "../views/Logs.vue"

export default createRouter({
 history: createWebHistory(),
 routes: [
 { path: "/", component: Dashboard },
 { path: "/deploy", component: Deploy },
 { path: "/registry", component: Registry },
 { path: "/parameters", component: Parameters },
 { path: "/logs", component: Logs }
 ]
})
