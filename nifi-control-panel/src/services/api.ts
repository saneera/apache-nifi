import axios from 'axios'
import { useLoadingStore } from '../store/loading'
import { useNifiStore } from '../store/nifi'
import { useToast } from 'vue-toastification'

const api = axios.create()

api.interceptors.request.use((config) => {
    const loader = useLoadingStore()
    const store = useNifiStore()

    loader.start()

    if (store.token) {
        config.headers.Authorization = `Bearer ${store.token}`
    }

    return config
})

api.interceptors.response.use(
    (response) => {
        const loader = useLoadingStore()
        loader.stop()
        return response
    },
    (error) => {
        const loader = useLoadingStore()
        loader.stop()

        const toast = useToast()

        //  AUTH ERROR
        if (error.response?.status === 401) {
            const store = useNifiStore()
            store.logout()
            window.location.href = '/login'
        }

        // GENERAL ERROR
        const message =
            error.response?.data?.message ||
            error.message ||
            'Something went wrong'

        toast.error(message)

        return Promise.reject(error)
    }
)

export default api
