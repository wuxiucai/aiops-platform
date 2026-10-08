import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/auth'

// 静态路由：登录页与主布局；业务路由由后端权限树动态注册
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/Login.vue')
    },
    {
      path: '/',
      name: 'Main',
      component: () => import('../layout/MainLayout.vue'),
      // 不能写死 redirect: '/monitor/dashboard'——首次导航到 / 时动态路由还没注册，
      // 会触发 R0004 No match found。用 beforeEach 里的"未注册 path fallback"逻辑兜底。
      children: []
    }
  ]
})

// 视图组件懒加载映射：key 形如 '../views/monitor/Dashboard.vue'
export const viewModules = import.meta.glob('../views/**/*.vue')

let dynamicRegistered = false

/** 从菜单树中挑出第一个"已注册 + 已授权"的 path，作为 / 默认跳转目标 */
function firstAvailablePath (menus) {
  for (const m of menus) {
    for (const c of (m.children || [])) {
      if (!c.path || !c.component) continue
      const p = c.path.startsWith('/') ? c.path : (m.path + c.path)
      return p
    }
  }
  return null
}

/** 根据后端菜单树递归注册动态路由（挂在 MainLayout 下，保持布局） */
export function registerDynamicRoutes(menus) {
  menus.forEach(menu => {
    (menu.children || []).forEach(child => {
      if (!child.path || !child.component) return
      // 兼容两种数据：子路由 path 为绝对路径（/monitor/dashboard）或相对路径（/dashboard）
      const fullPath = child.path.startsWith('/') ? child.path : (menu.path + child.path)
      if (router.hasRoute(fullPath)) return

      let component = viewModules[`../views/${child.component}.vue`]
      if (!component) {
        // 兜底：vite 的 import.meta.glob 只在 dev server 启动时扫描；
        // 后续新增的 .vue 文件必须动态 import 兜底，避免必须重启 dev server。
        console.warn('[router] glob 未收录，回退动态 import:', child.component)
        component = () => import(/* @vite-ignore */ `../views/${child.component}.vue`)
      }
      router.addRoute('Main', {
        path: fullPath,
        name: fullPath,
        component,
        meta: { title: child.name, perms: child.perms }
      })
    })
  })
  dynamicRegistered = true
}

router.beforeEach(async to => {
  // 登录页直接放行
  if (to.path === '/login') {
    return true
  }
  // 未登录 → 跳登录
  if (!getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录但权限树未加载（首次进入或刷新页面）→ 拉取并注册动态路由后重新解析
  const { useUserStore } = await import('../store/user')
  const userStore = useUserStore()
  if (!userStore.menus.length) {
    try {
      await userStore.fetchUserInfo()
    } catch (e) {
      return { path: '/login' }
    }
  }
  if (userStore.menus.length) {
    const firstTime = !dynamicRegistered
    // 幂等：hasRoute 会跳过已注册的路由；每次都跑一遍让新增菜单立即生效
    registerDynamicRoutes(userStore.menus)
    // 兜底：目标 path 仍未注册就重定向到第一个可用菜单。常见两种触发：
    //   1. 直接访问 /          → 没 redirect 字段留下来，默认渲染空 Main
    //   2. 访问历史/失效路径    → 注册集合里没有
    // 不能用 router.getRoutes() 判断，因为 path 形参比对的不是注册顺序；这里直接看 name
    const target = to.path === '/' ? firstAvailablePath(userStore.menus) : null
    if (target) {
      return { path: target, replace: true }
    }
    if (firstTime) {
      return { path: to.fullPath, replace: true }
    }
    if (!router.hasRoute(to.path)) {
      return { path: to.fullPath, replace: true }
    }
  }
  return true
})

export default router
