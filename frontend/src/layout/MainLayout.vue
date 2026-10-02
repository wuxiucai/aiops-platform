<template>
  <el-container class="main-layout">
    <!-- 侧边栏：由后端权限树驱动 -->
    <el-aside width="228px" class="aside">
      <div class="logo">
        <div class="logo-icon">A</div>
        <div class="logo-text">
          <span class="logo-title">AIOps 平台</span>
          <span class="logo-sub">智能运维 · 日志分析</span>
        </div>
      </div>
      <el-menu
        :default-active="$route.path"
        router
        class="side-menu"
        background-color="transparent"
        text-color="#a5b1c9"
        active-text-color="#ffffff"
      >
        <el-sub-menu v-for="menu in userStore.menus" :key="menu.id" :index="menu.path">
          <template #title>
            <el-icon><component :is="menu.icon || 'Menu'" /></el-icon>
            <span>{{ menu.name }}</span>
          </template>
          <el-menu-item v-for="child in menu.children || []" :key="child.id"
                        :index="child.path.startsWith('/') ? child.path : menu.path + child.path">
            <el-icon><component :is="child.icon || 'Document'" /></el-icon>
            <span>{{ child.name }}</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-if="$route.meta.title">{{ $route.meta.title }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <span class="user-entry">
              <span class="user-avatar">{{ avatarLetter }}</span>
              <span class="user-name">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
              <el-icon class="user-arrow"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 内容区 -->
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'

const userStore = useUserStore()
const router = useRouter()

// 用户信息与动态路由已由 router.beforeEach 全局守卫完成加载注册
const avatarLetter = computed(() => {
  const name = userStore.userInfo?.nickname || userStore.userInfo?.username || 'U'
  return name.charAt(0).toUpperCase()
})

async function handleCommand(cmd) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.main-layout {
  height: 100%;
}

/* ---------- 侧边栏 ---------- */
.aside {
  background: linear-gradient(180deg, #0f1d3a 0%, #16294f 100%);
  display: flex;
  flex-direction: column;
}
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.logo-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, #4361ee 0%, #7a5cff 100%);
  color: #fff;
  font-weight: 800;
  font-size: 17px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 10px rgba(67, 97, 238, 0.45);
  flex-shrink: 0;
}
.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
  overflow: hidden;
}
.logo-title {
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}
.logo-sub {
  color: rgba(165, 177, 201, 0.7);
  font-size: 11px;
  white-space: nowrap;
}
.side-menu {
  border-right: none;
  padding: 10px 10px 20px;
  flex: 1;
  overflow-y: auto;
}
.side-menu :deep(.el-sub-menu__title) {
  height: 44px;
  border-radius: 8px;
  margin-bottom: 2px;
}
.side-menu :deep(.el-sub-menu__title:hover) {
  background: rgba(255, 255, 255, 0.08);
}
.side-menu :deep(.el-menu) {
  background: transparent;
}
.side-menu :deep(.el-menu-item) {
  height: 40px;
  border-radius: 8px;
  margin-bottom: 2px;
}
.side-menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.08);
}
.side-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, #4361ee 0%, #5b7bff 100%);
  color: #fff;
  box-shadow: 0 4px 10px rgba(67, 97, 238, 0.35);
}

/* ---------- 顶栏 ---------- */
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #eef1f6;
  box-shadow: 0 1px 4px rgba(21, 32, 71, 0.05);
  z-index: 2;
}
.user-entry {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border-radius: 8px;
  transition: background .2s;
  outline: none;
}
.user-entry:hover {
  background: #f4f6fb;
}
.user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #4361ee 0%, #7a5cff 100%);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  font-size: 13px;
  color: #2b3245;
  font-weight: 500;
}
.user-arrow {
  color: #97a1b5;
  font-size: 12px;
}

/* ---------- 内容区 ---------- */
.main {
  background: #f3f5fa;
  overflow: auto;
  padding: 20px;
}
</style>
