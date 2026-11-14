<script setup>
defineProps({
  size: { type: Number, default: 16 },
  variant: { type: String, default: 'primary' },
})
</script>

<template>
  <span
    class="app-spinner"
    :class="variant"
    :style="{ width: size + 'px', height: size + 'px' }"
  ></span>
</template>

<style scoped>
.app-spinner {
  display: inline-block;
  border: 2px solid rgba(64, 158, 255, 0.25);
  border-top-color: #409eff;
  border-radius: 50%;
  animation: app-spin 0.7s linear infinite;
  vertical-align: middle;
  flex-shrink: 0;
}
.app-spinner.white {
  border-color: rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
}
@keyframes app-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
