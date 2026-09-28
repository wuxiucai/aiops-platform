import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, setToken, removeToken, removeUser } from './auth'
import router from '../router'

// axios 封装：自动加 token；code=200 解包 data；401 跳登录；其他 code 弹错误
// X-Refresh-Token：后端滑动续期下发的新 token，透明替换
const request = axios.create({
  timeout: 30000
})

request.interceptors.request.use(config => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  response => {
    const refreshToken = response.headers['x-refresh-token']
    if (refreshToken) {
      setToken(refreshToken)
    }
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    if (res.code === 401) {
      removeToken()
      removeUser()
      router.push('/login')
      return Promise.reject(new Error(res.msg || '未登录'))
    }
    ElMessage.error(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg || '请求失败'))
  },
  error => {
    // HTTP 层错误（SSE 401 已由业务层处理）
    const res = error.response?.data
    if (res && res.code === 401) {
      removeToken()
      removeUser()
      router.push('/login')
    } else {
      ElMessage.error(res?.msg || error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request
