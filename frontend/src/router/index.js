import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/auth'

// 静态路由：登录页与主布局；业务路由由后端权限树动态注册
export const staticRoutes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  }
]

export const viewModules = import.meta.glob('../views/**/*.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...staticRoutes,
    {
      path: '/',
      component: () => import('../layout/MainLayout.vue'),
      redirect: '/monitor/dashboard',
      children: []
    }
  ]
})

/** 根据后端菜单树递归注册动态路由 */
export function registerDynamicRoutes(menus) {
  const mainRoute = router.options.routes.find(r => r.path === '/')
  menus
    .filter(m => m.path && m.component && m.children && m.children.length >= 0)
    .forEach(menu => {
      (menu.children || []).forEach(child => {
        if (!child.path || !child.component) return
        const full = menu.path + child.path
        if (router.hasRoute(full)) return
        mainRoute.children.push({
          path: full,
          name: full,
          component: viewModules[`../views/${child.component}.vue`],
          meta: { title: child.name, perms: child.perms }
        })
      })
    })
  mainRoute.children.forEach(r => {
    if (!router.hasRoute(r.name)) router.addRoute(r)
  })
}

router.beforeEach(to => {
  const token = getToken()
  if (to.path === '/login') {
    return true
  }
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
