<template>
  <el-container style="height: 100vh">
    <!-- 侧边栏（浅色 Notion 产品风：surface 底 + hairline 右缘 + 白卡选中态） -->
    <el-aside width="230px" style="background: var(--nt-surface); border-right: 1px solid var(--nt-hairline); overflow-y: auto; display: flex; flex-direction: column">
      <!-- Logo -->
      <div style="padding: 24px 20px; text-align: center; border-bottom: 1px solid var(--nt-hairline)">
        <div style="font-size: 11px; color: var(--nt-steel); margin-bottom: 6px; letter-spacing: 2px; font-weight: 600">AI INTERVIEW</div>
        <h2 style="color: var(--nt-charcoal); font-size: 20px; margin: 0; font-weight: 600; letter-spacing: -0.3px">🤖 AI 面试官</h2>
      </div>

      <!-- 菜单 -->
      <el-menu
        :default-active="activeMenu"
        router
        style="border-right: none; flex: 1; padding: 8px 0; background: transparent"
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
      <div style="padding: 12px 20px; border-top: 1px solid var(--nt-hairline); text-align: center">
        <span style="font-size: 11px; color: var(--nt-stone)">Spring Boot 4 + Vue 3</span>
      </div>
    </el-aside>

    <!-- 主内容区（白画布） -->
    <el-main style="background: var(--nt-canvas); padding: 28px 32px; overflow-y: auto">
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
/* 菜单项：steel 文字 500，灰系 hover（Notion no-hover 政策之外的低调反馈） */
.nav-item {
  margin: 2px 12px;
  border-radius: 8px;
  transition: all .15s;
  height: 40px;
  color: var(--nt-steel) !important;
  font-weight: 500;
}
.nav-item :deep(.el-icon) {
  color: var(--nt-steel);
}
.nav-item:hover {
  background: rgba(15,15,15,.05) !important;
  color: var(--nt-charcoal) !important;
}
.nav-item:hover :deep(.el-icon) {
  color: var(--nt-charcoal);
}
/* 选中态：白卡 + hairline + 轻阴影 + 600 字重（Notion 桌面壳导航 pill） */
:deep(.el-menu-item.is-active),
:deep(.el-menu-item.is-active) .nav-item {
  background: var(--nt-canvas) !important;
  color: var(--nt-charcoal) !important;
  font-weight: 600;
  border: 1px solid var(--nt-hairline);
  box-shadow: var(--nt-shadow-1);
  border-radius: 8px;
  margin: 2px 12px;
  height: 40px;
}
:deep(.el-menu-item.is-active) .el-icon,
:deep(.el-menu-item.is-active) .nav-item .el-icon {
  color: var(--nt-primary);
}
/* 去掉默认左边框 */
:deep(.el-menu) {
  border-right: none !important;
}
</style>
