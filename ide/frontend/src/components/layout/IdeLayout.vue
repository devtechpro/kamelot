<script setup lang="ts">
import { Splitpanes, Pane } from 'splitpanes'
import 'splitpanes/dist/splitpanes.css'
import { useUiStore } from '@/stores/ui'
import { useProjectStore } from '@/stores/project'
import ProjectTree from '@/components/tree/ProjectTree.vue'
import CenterPanel from '@/components/layout/CenterPanel.vue'
import PropertiesPanel from '@/components/properties/PropertiesPanel.vue'
import RuntimePanel from '@/components/runtime/RuntimePanel.vue'
import Toolbar from '@/components/layout/Toolbar.vue'

const ui = useUiStore()
const projectStore = useProjectStore()
</script>

<template>
  <div class="ide">
    <Toolbar />
    <div class="ide-body">
      <div class="sidebar-left">
        <ProjectTree />
      </div>
      <div class="divider"></div>
      <div class="center">
        <Splitpanes horizontal>
          <Pane :size="ui.bottomPanelVisible ? 78 : 100" min-size="30">
            <CenterPanel />
          </Pane>
          <Pane v-if="ui.bottomPanelVisible" :size="22" min-size="8" max-size="45">
            <RuntimePanel />
          </Pane>
        </Splitpanes>
      </div>
      <div class="divider" v-if="projectStore.currentProject"></div>
      <div class="sidebar-right" v-if="projectStore.currentProject">
        <PropertiesPanel />
      </div>
    </div>
  </div>
</template>

<style scoped>
.ide {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
}

.ide-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.sidebar-left {
  width: 220px;
  min-width: 160px;
  flex-shrink: 0;
  overflow: hidden;
}

.center {
  flex: 1;
  min-width: 200px;
  overflow: hidden;
}

.sidebar-right {
  width: 280px;
  min-width: 200px;
  flex-shrink: 0;
  overflow: hidden;
}

.divider {
  width: 1px;
  background: var(--border);
  flex-shrink: 0;
}
</style>
