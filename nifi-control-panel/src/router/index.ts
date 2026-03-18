import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import Registry from '../views/Registry.vue'
import Upload from '../views/Upload.vue'
import Params from '../views/Params.vue'
import Login from '../views/Login.vue'
import { useNifiStore } from '../store/nifi'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    { path: '/', component: Dashboard },
    { path: '/registry', component: Registry },
    { path: '/upload', component: Upload },
    { path: '/params', component: Params },
  ]
})

router.beforeEach((to, _, next) => {
  const store = useNifiStore()
  if (!store.token && to.path !== '/login') next('/login')
  else next()
})

export default router
