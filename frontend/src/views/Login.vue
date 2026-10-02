<template>
  <div class="login-container">
    <!-- 背景装饰 -->
    <div class="bg-blob blob-1"></div>
    <div class="bg-blob blob-2"></div>

    <div class="login-panel">
      <!-- 左侧品牌区 -->
      <div class="brand-side">
        <div class="brand-logo">
          <div class="brand-icon">A</div>
          <span class="brand-name">AIOps</span>
        </div>
        <h1 class="brand-title">智能运维告警<br />与日志分析平台</h1>
        <p class="brand-desc">
          基于 SpringBoot + Vue 的一站式运维平台<br />
          监控告警 · 日志分析 · AI 辅助诊断
        </p>
        <ul class="brand-points">
          <li><el-icon><Monitor /></el-icon>实时监控大盘与指标趋势</li>
          <li><el-icon><Bell /></el-icon>告警聚合、抑制与工单流转</li>
          <li><el-icon><ChatDotRound /></el-icon>LLM 自然语言查询与根因分析</li>
        </ul>
      </div>

      <!-- 右侧登录表单 -->
      <div class="form-side">
        <h2 class="form-title">欢迎回来</h2>
        <p class="form-sub">请登录你的账号</p>
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
            <el-button type="primary" size="large" style="width: 100%; height: 44px; font-size: 15px;"
                       :loading="loading" @click="handleLogin">
              登 录
            </el-button>
          </el-form-item>
        </el-form>
        <div class="tips">默认账号：admin / 123456</div>
      </div>
    </div>
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
  background: linear-gradient(120deg, #0f1d3a 0%, #1a2f5c 45%, #4338c9 100%);
  position: relative;
  overflow: hidden;
}

/* 背景光斑 */
.bg-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.45;
  pointer-events: none;
}
.blob-1 {
  width: 420px;
  height: 420px;
  background: #4361ee;
  top: -120px;
  right: -80px;
  animation: float 12s ease-in-out infinite;
}
.blob-2 {
  width: 360px;
  height: 360px;
  background: #7a5cff;
  bottom: -100px;
  left: -60px;
  animation: float 14s ease-in-out infinite reverse;
}
@keyframes float {
  0%, 100% { transform: translate(0, 0); }
  50%      { transform: translate(30px, 20px); }
}

/* 登录面板（左品牌 + 右表单） */
.login-panel {
  position: relative;
  display: flex;
  width: 880px;
  max-width: calc(100vw - 40px);
  min-height: 520px;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 24px 64px rgba(5, 10, 30, 0.5);
  animation: panel-in .5s ease;
}
@keyframes panel-in {
  from { opacity: 0; transform: translateY(18px) scale(0.98); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}

/* 左侧品牌区 */
.brand-side {
  flex: 1.1;
  padding: 52px 46px;
  color: #fff;
  background: linear-gradient(160deg, rgba(67, 97, 238, 0.30) 0%, rgba(122, 92, 255, 0.20) 100%);
  backdrop-filter: blur(6px);
  border-right: 1px solid rgba(255, 255, 255, 0.12);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.brand-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 34px;
}
.brand-icon {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  background: linear-gradient(135deg, #5b7bff 0%, #7a5cff 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 800;
  box-shadow: 0 6px 16px rgba(67, 97, 238, 0.5);
}
.brand-name {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 1px;
}
.brand-title {
  font-size: 27px;
  line-height: 1.45;
  font-weight: 700;
  margin-bottom: 16px;
}
.brand-desc {
  font-size: 13px;
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.75);
  margin-bottom: 30px;
}
.brand-points {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.9);
}
.brand-points .el-icon {
  font-size: 16px;
  color: #9db1ff;
}

/* 右侧表单区 */
.form-side {
  flex: 1;
  padding: 52px 46px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(10px);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.form-title {
  font-size: 24px;
  font-weight: 700;
  color: #2b3245;
}
.form-sub {
  font-size: 13px;
  color: #97a1b5;
  margin: 6px 0 28px;
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
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e5e9f2;
  flex-shrink: 0;
}
.captcha-img img {
  width: 100%;
  height: 100%;
}
.tips {
  text-align: center;
  color: #97a1b5;
  font-size: 12px;
  margin-top: 4px;
}

/* 窄屏：只显示表单 */
@media (max-width: 760px) {
  .brand-side { display: none; }
  .login-panel { width: 420px; min-height: auto; }
}
</style>
