<template>
  <div class="geo-map">
    <MapSearch class="search-bar" @fly-to-node="flyToNode" @set-bounding-box="setBoundingBox" />
    <SeverityFilter />
    <LMap
      ref="map"
      :center="center"
      :max-zoom="19"
      :min-zoom="2"
      :zoomAnimation="true"
      @ready="onLeafletReady"
      @moveend="onMoveEnd"
      @zoom="invalidateSizeFn"
    >
      <template v-if="leafletReady">
        <LControlLayers />
        <LTileLayer
          v-for="tileProvider in tileProviders"
          :key="tileProvider.name"
          :name="tileProvider.name"
          :visible="tileProvider.visible"
          :url="tileProvider.url"
          :attribution="tileProvider.attribution"
          layer-type="base"
        />
        <MarkerCluster
          :options="{ showCoverageOnHover: false, chunkedLoading: true, iconCreateFunction }"
        >
          <LMarker
            v-for="node of nodes"
            :key="node.label"
            :lat-lng="[node.assetRecord.latitude, node.assetRecord.longitude]"
            :name="node.label"
          >
            <LPopup>
              Node:
              <router-link :to="`/node/${node.id}`" target="_blank">{{ node.label }}</router-link>
              <br />
              Severity: {{ nodeLabelAlarmServerityMap[node.label] || 'NORMAL' }}
              <br />
              Category: {{ node.categories.length ? node.categories[0].name : 'N/A' }}
            </LPopup>
            <LIcon :icon-url="setIcon(node)" :icon-size="iconSize" />
          </LMarker>
          <!-- Disable polylines until they work -->
          <!-- <LPolyline
              v-if="zoom > 5"
              v-for="coordinatePair of edges"
              :key="coordinatePair[0].toString()"
              :lat-lngs="[coordinatePair[0], coordinatePair[1]]"
              color="green"
          />-->
        </MarkerCluster>
      </template>
    </LMap>
  </div>
</template>
<script setup lang ="ts">
import 'leaflet/dist/leaflet.css'
import {
  LMap,
  LTileLayer,
  LMarker,
  LIcon,
  LPopup,
  LControlLayers,
  // LPolyline,
} from '@vue-leaflet/vue-leaflet'
import MarkerCluster from './MarkerCluster.vue'
import { useStore } from 'vuex'
import { Node } from '@/types'
import NormalIcon from '@/assets/Normal-icon.png'
import WarninglIcon from '@/assets/Warning-icon.png'
import MinorIcon from '@/assets/Minor-icon.png'
import MajorIcon from '@/assets/Major-icon.png'
import CriticalIcon from '@/assets/Critical-icon.png'
import { Map as LeafletMap, divIcon, MarkerCluster as Cluster } from 'leaflet'
import MapSearch from './MapSearch.vue'
import { numericSeverityLevel } from './utils'
import SeverityFilter from './SeverityFilter.vue'

const store = useStore()
const map = ref()
const route = useRoute()
const leafletReady = ref<boolean>(false)
const leafletObject = ref({} as LeafletMap)
const zoom = ref<number>(2)
const iconWidth = 25
const iconHeight = 42
const iconSize = [iconWidth, iconHeight]
const center = computed<number[]>(() => ['latitude', 'longitude'].map(k => store.state.mapModule.mapCenter[k]))
const nodes = computed<Node[]>(() => store.getters['mapModule/getNodes'])
const allNodes = computed<Node[]>(() => store.state.mapModule.nodesWithCoordinates)
const bounds = computed(() => {
  const coordinatedMap = getNodeCoordinateMap.value
  return nodes.value.map((node) => coordinatedMap.get(node.id))
})
const nodeLabelAlarmServerityMap = computed(() => store.getters['mapModule/getNodeAlarmSeverityMap'])

const getHighestSeverity = (severitites: string[]) => {
  let highestSeverity = 'NORMAL'
  for (const severity of severitites) {
    if (numericSeverityLevel(severity) > numericSeverityLevel(highestSeverity)) {
      highestSeverity = severity
    }
  }
  return highestSeverity
}

// for custom marker cluster icon
const iconCreateFunction = (cluster: Cluster) => {
  const childMarkers = cluster.getAllChildMarkers()
  // find highest level of severity
  const severitites = []
  for (const marker of childMarkers) {
    const markerSeverity = nodeLabelAlarmServerityMap.value[(marker as any).options.name]
    if (markerSeverity) {
      severitites.push(markerSeverity)
    }
  }
  const highestSeverity = getHighestSeverity(severitites)
  return divIcon({ html: `<span class=${highestSeverity}>` + cluster.getChildCount() + '</span>' })
}

const setIcon = (node: Node) => setMarkerColor(nodeLabelAlarmServerityMap.value[node.label])

const setMarkerColor = (severity: string | undefined) => {
  if (severity) {
    switch (severity.toUpperCase()) {
      case 'NORMAL':
        return NormalIcon
      case 'WARNING':
        return WarninglIcon
      case 'MINOR':
        return MinorIcon
      case 'MAJOR':
        return MajorIcon
      case 'CRITICAL':
        return CriticalIcon
      default:
        return NormalIcon
    }
  }
  return NormalIcon
}

// const edges = computed(() => {
//   const ids: string[] = nodes.value.map((node: Node) => node.id)
//   const interestedNodesCoordinateMap = getNodeCoordinateMap.value
//   return store.state.mapModule.edges.filter((edge: [number, number]) => ids.includes(edge[0].toString()) && ids.includes(edge[1].toString()))
//     .map((edge: [number, number]) => {
//       let edgeCoordinatesPair = []
//       edgeCoordinatesPair.push(interestedNodesCoordinateMap.get(edge[0]))
//       edgeCoordinatesPair.push(interestedNodesCoordinateMap.get(edge[1]))
//       return edgeCoordinatesPair
//     })
// })

const getNodeCoordinateMap = computed(() => {
  const map = new Map()
  allNodes.value.forEach((node: Node) => {
    map.set(node.id, [node.assetRecord.latitude, node.assetRecord.longitude])
    map.set(node.label, [node.assetRecord.latitude, node.assetRecord.longitude])
  })
  return map
})

const onLeafletReady = async () => {
  await nextTick()
  leafletObject.value = map.value.leafletObject
  if (leafletObject.value != undefined && leafletObject.value != null) {
    // set default map view port
    leafletObject.value.zoomControl.setPosition('topright')
    leafletReady.value = true

    await nextTick()

    // save the bounds to state
    store.dispatch('mapModule/setMapBounds', leafletObject.value.getBounds())

    try {
      leafletObject.value.fitBounds(bounds.value)
    } catch (err) {
      console.log(err, `Invalid bounds array: ${bounds.value}`)
    }

    // if nodeid query param, fly to it
    if (route.query.nodeid) {
      flyToNode(route.query.nodeid as string)
    }
  }
}

const onMoveEnd = () => {
  zoom.value = leafletObject.value.getZoom()
  store.dispatch('mapModule/setMapBounds', leafletObject.value.getBounds())
}

const flyToNode = (nodeLabelOrId: string) => {
  const coordinateMap = getNodeCoordinateMap.value
  const nodeCoordinates = coordinateMap.get(nodeLabelOrId)

  if (nodeCoordinates) {
    leafletObject.value.flyTo(nodeCoordinates, 7)
  }
}

const setBoundingBox = (nodeLabels: string[]) => {
  const coordinateMap = getNodeCoordinateMap.value
  const bounds = nodeLabels.map((nodeLabel) => coordinateMap.get(nodeLabel))
  if (bounds.length) {
    leafletObject.value.fitBounds(bounds)
  }
}

const invalidateSizeFn = () => leafletObject.value.invalidateSize()

/*****Tile Layer*****/
const tileProviders = [
  {
    name: 'OpenStreetMap',
    visible: true,
    attribution:
      '&copy; <a target="_blank" href="http://osm.org/copyright">OpenStreetMap</a> contributors',
    url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
  },
  {
    name: 'OpenTopoMap',
    visible: false,
    url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
    attribution:
      'Map data: &copy; <a href="http://www.openstreetmap.org/copyright">OpenStreetMap</a>, <a href="http://viewfinderpanoramas.org">SRTM</a> | Map style: &copy; <a href="https://opentopomap.org">OpenTopoMap</a> (<a href="https://creativecommons.org/licenses/by-sa/3.0/">CC-BY-SA</a>)',
  },
]

defineExpose({ invalidateSizeFn })
</script>

<style scoped>
.search-bar {
  position: absolute;
  margin-left: 10px;
  margin-bottom: 23px;
  margin-top: -5px;
}
.geo-map {
  height: 100%;
}
</style>

<style lang="scss">
@import "@featherds/styles/themes/variables";
.leaflet-marker-pane {
  div {
    width: 30px !important;
    height: 30px !important;
    margin-left: -15px !important;
    margin-top: -15px !important;
    text-align: center;
    font: 12px "Helvetica Neue", Arial, Helvetica, sans-serif;
    border-radius: 15px;
    border: none;
    span {
      border-radius: 15px;
      line-height: 30px;
      width: 100%;
      display: block;
      &.NORMAL {
        background: var($success);
      }
      &.WARNING,
      &.MINOR,
      &.MAJOR {
        background: var($warning);
      }
      &.CRITICAL {
        background: var($error);
      }
      opacity: 0.7;
    }
  }
}
</style>
