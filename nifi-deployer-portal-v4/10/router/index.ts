
import { createRouter,createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import Deploy from '../views/Deploy.vue'

export default createRouter({
 history:createWebHistory(),
 routes:[
  {path:'/',component:Dashboard},
  {path:'/deploy',component:Deploy}
 ]
})
