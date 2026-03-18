import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import './style.css'
import Toast from 'vue-toastification'
import 'vue-toastification/dist/index.css'

createApp(App)
    .use(router)
    .use(createPinia())
    .use(Toast)
    .mount('#app')
