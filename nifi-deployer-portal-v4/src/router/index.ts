
import { createRouter,createWebHistory } from 'vue-router'

import Dashboard from '../views/Dashboard.vue'
import Deploy from '../views/Deploy.vue'
import Registry from '../views/Registry.vue'
import History from '../views/History.vue'
import Logs from '../views/Logs.vue'

export default createRouter({
 history:createWebHistory(),
 routes:[
  {path:'/',component:Dashboard},
  {path:'/deploy',component:Deploy},
  {path:'/registry',component:Registry},
  {path:'/history',component:History},
  {path:'/logs',component:Logs}
 ]
})
