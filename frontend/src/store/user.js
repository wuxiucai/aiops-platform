import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as loginApi, getUserInfo } from '../api/auth'
import { getToken, setToken, removeToken, setUser, removeUser, getUser } from '../utils/auth'

export const useUserStore = defineStore('user', () => {
  const token = ref(getToken() || '')
  const userInfo = ref(getUser() || null)
  const perms = ref([])
  const menus = ref([])

  async function login(form) {
    const data = await loginApi(form)
    token.value = data.token
    setToken(data.token)
  }

  async function fetchUserInfo() {
    const data = await getUserInfo()
    userInfo.value = {
      userId: data.userId,
      username: data.username,
      nickname: data.nickname,
      avatar: data.avatar,
      roles: [...data.roles]
    }
    perms.value = [...data.perms]
    menus.value = data.menus || []
    setUser(userInfo.value)
    return data
  }

  function hasPerm(perm) {
    return perms.value.includes('*:*:*') || perms.value.includes(perm)
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    perms.value = []
    menus.value = []
    removeToken()
    removeUser()
  }

  return { token, userInfo, perms, menus, login, fetchUserInfo, hasPerm, logout }
})
