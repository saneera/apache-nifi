import { defineStore } from 'pinia'
import axios from 'axios'

export const useNifiStore = defineStore('nifi', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    nifiUrl: import.meta.env.VITE_NIFI_URL,
    registryUrl: import.meta.env.VITE_REGISTRY_URL,
    registryBucket: import.meta.env.VITE_REGISTRY_BUCKET || ''
  }),
  actions: {
    async login(username: string, password: string) {
      const res = await axios.post(
        `${this.nifiUrl}/nifi-api/access/token`,
        `username=${username}&password=${password}`,
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }
      )
      this.token = res.data
      localStorage.setItem('token', this.token)
    },
    logout() {
      this.token = ''
      localStorage.removeItem('token')
      window.location.href = '/login'
    }
  }
})
