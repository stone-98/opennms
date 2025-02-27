<template>
  <div class="feather-row">
    <div class="feather-col-12">
      <splitpanes
        :dbl-click-splitter="true"
        @pane-maximize="minimizeBottomPane"
        class="default-theme"
        horizontal
        style="height: calc(100vh - 80px)"
        ref="split"
        @resize="resize"
      >
        <pane min-size="1" max-size="100" :size="72">
          <LeafletMap v-if="nodesReady" ref="leafletComponent" />
        </pane>
        <pane min-size="1" max-size="100" :size="28" class="bottom-pane">
          <GridTabs />
        </pane>
      </splitpanes>
    </div>
  </div>
</template>

<!-- used to keep map alive once loaded -->
<script lang="ts">
export default { name: 'MapKeepAlive' }
</script>

<script setup lang="ts">
import { useStore } from 'vuex'
import { Splitpanes, Pane } from 'splitpanes'
import 'splitpanes/dist/splitpanes.css'
import LeafletMap from '../components/Map/LeafletMap.vue'
import GridTabs from '@/components/Map/GridTabs.vue'
import { debounce } from 'lodash'

const store = useStore()
const split = ref()
const nodesReady = ref(false)
const leafletComponent = ref()

const minimizeBottomPane = () => {
  // override splitpane event
  split.value.panes[0].size = 96
  split.value.panes[1].size = 4
  setTimeout(() => leafletComponent.value.invalidateSizeFn(), 200)
}

// resize the map when splitter dragged
const resize = debounce(() => leafletComponent.value.invalidateSizeFn(), 200)

onMounted(async () => {
  store.dispatch('spinnerModule/setSpinnerState', true)
  await store.dispatch('mapModule/getNodes')
  await store.dispatch('mapModule/getAlarms')
  store.dispatch('spinnerModule/setSpinnerState', false)
  nodesReady.value = true
  // commented out until we do topology
  // store.dispatch('mapModule/getNodesGraphEdges')
})

onActivated(() => store.dispatch('appModule/setNavRailOpen', false))
onDeactivated(() => store.dispatch('appModule/setNavRailOpen', true))
</script>

<style scoped lang="scss">
.bottom-pane {
  position: relative;
}
</style>

<style lang="scss">
@import "@featherds/styles/themes/variables";
.default-theme {
  .splitpanes__splitter {
    height: 10px !important;
    background: var($shade-3) !important;
  }
  .splitpanes__splitter::after,
  .splitpanes__splitter::before {
    background: var($primary-text-on-surface) !important;
  }
}
</style>
