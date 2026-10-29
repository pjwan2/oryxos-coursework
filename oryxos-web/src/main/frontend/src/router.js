import { createRouter, createWebHistory } from 'vue-router'
import MemoryView from './views/MemoryView.vue'
import ProfilesView from './views/ProfilesView.vue'
import SchedulerView from './views/SchedulerView.vue'
import SessionsView from './views/SessionsView.vue'
import SystemView from './views/SystemView.vue'
import ToolsView from './views/ToolsView.vue'

// History mode under /admin/. Spring Boot answers any extension-less /admin/** path with
// index.html, so reloading a deep link works. Route params must not contain dots (the server
// would take them for a file name), so scheduled task ids are kept in page state, not in the URL.
export const routes = [
  { path: '/', redirect: '/sessions' },
  { path: '/sessions/:id?', name: 'sessions', component: SessionsView },
  { path: '/profiles', name: 'profiles', component: ProfilesView },
  { path: '/tools', name: 'tools', component: ToolsView },
  { path: '/memory', name: 'memory', component: MemoryView },
  { path: '/system', name: 'system', component: SystemView },
  { path: '/scheduler', name: 'scheduler', component: SchedulerView },
  { path: '/:pathMatch(.*)*', redirect: '/sessions' }
]

export const router = createRouter({
  history: createWebHistory('/admin/'),
  routes
})
