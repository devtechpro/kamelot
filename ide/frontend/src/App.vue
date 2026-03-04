<script setup lang="ts">
import { onMounted } from 'vue'
import { useProjectStore } from '@/stores/project'
import IdeLayout from '@/components/layout/IdeLayout.vue'

const projectStore = useProjectStore()

onMounted(() => {
  projectStore.fetchProjects()
})
</script>

<template>
  <IdeLayout />
</template>
