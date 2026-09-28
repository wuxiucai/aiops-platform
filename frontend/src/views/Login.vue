<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2 class="title">智能运维告警与日志分析平台</h2>
      <el-form :model="form" @keyup.enter="handleLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password>
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" placeholder="验证码" size="large" />
            <div class="captcha-img" @click="refreshCaptcha" title="点击刷新">
              <img v-if="captchaSvg" :src="'data:image/svg+xml;base64,' + captchaSvg" alt="验证码" />
            </div>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="tips">默认账号：admin / 123456</div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCaptcha } from '../api/auth'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = reactive({
  username: 'admin',
  password: '',
  captchaCode: '',
  captchaKey: ''
})
const captchaSvg = ref('')
const loading = ref(false)

async function refreshCaptcha() {
  const data = await getCaptcha()
  captchaSvg.value = data.svg
  form.captchaKey = data.key
  form.captchaCode = ''
}

async function handleLogin() {
  if (!form.username || !form.password || !form.captchaCode) {
    ElMessage.warning('请填写完整登录信息')
    return
  }
  loading.value = true
  try {
    await userStore.login(form)
    await userStore.fetchUserInfo()
    router.push(route.query.redirect || '/')
  } finally {
    loading.value = false
    refreshCaptcha()
  }
}

onMounted(refreshCaptcha)
</script>

<style scoped>
.login-container {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2d3a4b 100%);
}
.login-card {
  width: 400px;
  padding: 10px 20px;
}
.title {
  text-align: center;
  margin-bottom: 24px;
  color: #303133;
}
.captcha-row {
  display: flex;
  width: 100%;
  gap: 10px;
}
.captcha-img {
  width: 120px;
  height: 40px;
  cursor: pointer;
  border-radius: 4px;
  overflow: hidden;
}
.captcha-img img {
  width: 100%;
  height: 100%;
}
.tips {
  text-align: center;
  color: #909399;
  font-size: 12px;
}
</style>
