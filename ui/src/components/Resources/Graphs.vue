<template>
  <div class="feather-row">
    <div class="feather-col-11">
      <div class="controls">
        <TimeControls @updateTime="updateTime" />
        <FeatherInput
          v-if="!singleGraphDefinition"
          class="search-input"
          label="Search"
          v-model="searchVal"
          @update:modelValue="searchHandler"
        />
      </div>
      <GraphContainer
        v-for="resource in resources"
        :resource="resource"
        :key="resource.id"
        :time="time"
        :definitionsToDisplay="definitionsToDisplay"
        :isSingleGraph="Boolean(singleGraphDefinition)"
        @addGraphDefinition="addGraphDefinition"
      />
    </div>
  </div>
</template>
  
<script setup lang=ts>
import { useStore } from 'vuex'
import GraphContainer from './GraphContainer.vue'
import TimeControls from './TimeControls.vue'
import { sub, getUnixTime } from 'date-fns'
import { StartEndTime } from '@/types'
import { FeatherInput } from '@featherds/input'

const el = document.getElementById('card')
const { arrivedState } = useScroll(el, { offset: { bottom: 100 } })
const definitionsToDisplay = ref<string[]>([])
const store = useStore()
const router = useRouter()
const now = new Date()
const initNumOfGraphs = 4
const searchVal = ref<string>('')

const props = defineProps({
  singleGraphDefinition: {
    type: String
  },
  singleGraphResourceId: {
    type: String
  },
  label: {
    type: String
  }
})

const resources = props.singleGraphResourceId ?
  ref([{ id: props.singleGraphResourceId, definitions: [props.singleGraphDefinition as string], label: props.label as string }]) :
  computed<{ id: string, definitions: string[], label: string }[]>(() => store.state.graphModule.definitions)

const definitionsList = computed<string[]>(() => store.state.graphModule.definitionsList)
let definitionsListCopy: string[] = JSON.parse(JSON.stringify(store.state.graphModule.definitionsList))

const time = reactive<StartEndTime>({
  startTime: getUnixTime(sub(now, { hours: 24 })),
  endTime: getUnixTime(now),
  format: 'hours'
})

const updateTime = (newStartEndTime: StartEndTime) => {
  time.endTime = newStartEndTime.endTime
  time.startTime = newStartEndTime.startTime
  time.format = newStartEndTime.format
}

const addGraphDefinition = () => {
  const next = definitionsListCopy.shift()
  if (next) {
    definitionsToDisplay.value = [...definitionsToDisplay.value, next]
  }
}

const searchHandler = (searchInputVal: string) => {
  store.dispatch('spinnerModule/setSpinnerState', true)
  searchVal.value = searchInputVal

  const search = useDebounceFn((val: string) => {
    if (val) {
      definitionsListCopy = definitionsList.value.filter((definition) =>
        definition.toLowerCase().includes(val.toLowerCase()))

      definitionsToDisplay.value = definitionsListCopy.splice(0, 4)
    }

    if (!val) {
      definitionsListCopy = JSON.parse(JSON.stringify(definitionsList.value))
      definitionsToDisplay.value = definitionsListCopy.splice(0, 4)
    }

    store.dispatch('spinnerModule/setSpinnerState', false)
  }, 1000)

  search(searchInputVal)
}

watch(arrivedState, () => {
  // add a new graph when the scroll bar hits the bottom
  if (arrivedState.bottom && !props.singleGraphDefinition) {
    addGraphDefinition()
  }
})

onMounted(() => {
  // for displaying only selected graph
  if (props.singleGraphDefinition) {
    definitionsToDisplay.value = [props.singleGraphDefinition]
    return
  }

  [...Array(initNumOfGraphs)].forEach(() => {
    addGraphDefinition()
  })
})

onBeforeMount(() => {
  if (props.singleGraphDefinition) return

  // if no resources, route to resource selection
  const resources = store.state.resourceModule.resources

  if (!resources.length) {
    router.push('/resource-graphs')
  }
})
</script>

<style scoped lang="scss">
.controls {
  display: flex;
  justify-content: space-between;

  .search-input {
    width: 230px;
    margin-top: 5px;
    margin-bottom: -7px;
  }
}
</style>
  