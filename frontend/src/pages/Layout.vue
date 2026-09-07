<template>
  <el-container style="height: 100vh">
    <!-- 侧边栏 -->
    <el-aside width="230px" style="background: linear-gradient(180deg, #1e293b 0%, #0f172a 100%); color: #fff; overflow-y: auto; display: flex; flex-direction: column">
      <!-- Logo -->
      <div style="padding: 24px 20px; text-align: center; border-bottom: 1px solid rgba(255,255,255,.08)">
        <div style="font-size: 14px; color: #94a3b8; margin-bottom: 4px; letter-spacing: 2px">AI INTERVIEW</div>
        <h2 style="color: #60a5fa; font-size: 20px; margin: 0; font-weight: 600">🤖 AI 面试官</h2>
      </div>

      <!-- 菜单 -->
      <el-menu
        :default-active="activeMenu"
        background-color="transparent"
        text-color="#94a3b8"
        active-text-color="#fff"
        router
        style="border-right: none; flex: 1; padding: 8px 0"
      >
        <el-menu-item index="/upload" class="nav-item">
          <el-icon><Upload /></el-icon>
          <span>简历管理</span>
        </el-menu-item>
        <el-menu-item index="/knowledge" class="nav-item">
          <el-icon><Collection /></el-icon>
          <span>知识库</span>
        </el-menu-item>
        <el-menu-item index="/interview" class="nav-item">
          <el-icon><ChatDotRound /></el-icon>
          <span>模拟面试</span>
        </el-menu-item>
        <el-menu-item index="/interviews" class="nav-item">
          <el-icon><Clock /></el-icon>
          <span>面试记录</span>
        </el-menu-item>
        <el-menu-item index="/schedule" class="nav-item">
          <el-icon><Calendar /></el-icon>
          <span>面试日程</span>
        </el-menu-item>
        <el-menu-item index="/health" class="nav-item" style="margin-top: auto">
          <el-icon><Monitor /></el-icon>
          <span>系统状态</span>
        </el-menu-item>
      </el-menu>

      <!-- 底部信息 -->
      <div style="padding: 12px 20px; border-top: 1px solid rgba(255,255,255,.08); text-align: center">
        <span style="font-size: 11px; color: #475569">Spring Boot 4 + Vue 3</span>
      </div>
    </el-aside>

    <!-- 主内容区 -->
    <el-main style="background: #f8fafc; padding: 28px 32px; overflow-y: auto">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  Upload, Collection, ChatDotRound, Clock, Calendar, Monitor
} from '@element-plus/icons-vue'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>

<style scoped>
/* 菜单项悬浮效果 */
.nav-item {
  margin: 2px 12px;
  border-radius: 8px;
  transition: all .2s;
  height: 44px;
}
.nav-item:hover {
  background: rgba(255,255,255,.06) !important;
}
/* 选中态 */
:deep(.el-menu-item.is-active) {
  background: rgba(96,165,250,.15) !important;
  border-radius: 8px;
  margin: 2px 12px;
}
/* 去掉默认左边框 */
:deep(.el-menu) {
  border-right: none !important;
}
</style>
