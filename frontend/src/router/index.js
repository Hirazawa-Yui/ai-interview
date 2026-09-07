import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('../pages/Layout.vue'),
    redirect: '/upload',
    children: [
      {
        path: 'upload',
        name: 'Upload',
        component: () => import('../pages/UploadPage.vue'),
        meta: { title: '简历上传' }
      },
      {
        path: 'knowledge',
        name: 'Knowledge',
        component: () => import('../pages/KnowledgePage.vue'),
        meta: { title: '知识库' }
      },
      {
        path: 'interview',
        name: 'Interview',
        component: () => import('../pages/InterviewPage.vue'),
        meta: { title: '模拟面试' }
      },
      {
        path: 'interviews',
        name: 'Interviews',
        component: () => import('../pages/InterviewHistoryPage.vue'),
        meta: { title: '面试记录' }
      },
      {
        path: 'schedule',
        name: 'Schedule',
        component: () => import('../pages/SchedulePage.vue'),
        meta: { title: '面试日程' }
      },
      {
        path: 'health',
        name: 'Health',
        component: () => import('../pages/HealthPage.vue'),
        meta: { title: '系统状态' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
