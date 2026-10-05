<template>
  <div class="refresh-selector">
    <el-tooltip content="自动刷新间隔；1s 仅适合临时排障" placement="top">
      <el-radio-group
        :model-value="modelValue"
        size="small"
        @update:model-value="onChange"
      >
        <el-radio-button :value="0">
          <el-icon><VideoPause /></el-icon>
        </el-radio-button>
        <el-radio-button :value="1000">1s</el-radio-button>
        <el-radio-button :value="5000">5s</el-radio-button>
        <el-radio-button :value="15000">15s</el-radio-button>
        <el-radio-button :value="30000">30s</el-radio-button>
        <el-radio-button :value="60000">60s</el-radio-button>
      </el-radio-group>
    </el-tooltip>
  </div>
</template>

<script setup>
import { VideoPause } from '@element-plus/icons-vue'

/**
 * 通用自动刷新频率选择器
 *
 * 用法：
 *   <RefreshSelector v-model="refreshMs" @change="onRefreshChange" />
 *   // refreshMs = 0 表示暂停; 其他值单位毫秒
 *   function onRefreshChange(ms) {
 *     if (timer) clearInterval(timer)
 *     if (ms > 0) timer = setInterval(load, ms)
 *   }
 *
 * 与 localStorage 的集成由调用方做（每个页面存自己的 key）。
 */

const props = defineProps({
  modelValue: { type: Number, default: 30000 }
})
const emit = defineEmits(['update:modelValue', 'change'])

function onChange(v) {
  emit('update:modelValue', v)
  emit('change', v)
}
</script>

<style scoped>
.refresh-selector { display: inline-block; }
.refresh-selector :deep(.el-radio-button__inner) { padding: 5px 10px; font-size: 12px; }
</style>
