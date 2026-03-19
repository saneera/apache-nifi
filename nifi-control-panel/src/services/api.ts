import axios from 'axios'
import { useLoadingStore } from '../store/loading'
import { useNifiStore } from '../store/nifi'
import { useToast } from 'vue-toastification'

const api = axios.create()

api.interceptors.request.use((config) => {
    const store = useNifiStore()

    if (store.token) {
        config.headers.Authorization = `Bearer ${store.token}`
    }

    config.headers['Content-Type'] = 'application/json'

    // 🔥 Add CSRF token from cookie
    const match = document.cookie.match(/__Secure-Request-Token=([^;]+)/)

    if (match) {
        config.headers['Request-Token'] = match[1]
    }

    return config
})



async getLatestVersion(flowId: string) {
    const store = useNifiStore()

    try {
        const res = await api.get(
            `${store.registryUrl}/nifi-registry-api/buckets/${store.bucketId}/flows/${flowId}/versions/latest`
        )

        return res.data.snapshotMetadata.version
    } catch (e: any) {

        // 🔥 handle 404 (no versions yet)
        if (e.response?.status === 404) {
            return 0
        }

        throw e
    }
}
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
