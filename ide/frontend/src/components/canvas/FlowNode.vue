<script setup lang="ts">
defineProps<{
  tall?: boolean
  dashed?: boolean
}>()
</script>

<template>
  <div class="flow-node" :class="{ tall, dashed }">
    <slot />
  </div>
</template>

<style scoped>
.flow-node {
  width: 90px;
  height: 48px;
  flex-shrink: 0;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 8px 14px;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  gap: 3px;
  cursor: pointer;
}

.flow-node.tall {
  height: 64px;
}

.flow-node.dashed {
  border-style: dashed;
  justify-content: center;
  align-items: center;
  padding: 0;
}
</style>
