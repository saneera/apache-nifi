
import { reactive } from 'vue'

export const flowState = reactive({
 flow:null
})

export function setFlow(flow){
 flowState.flow=flow
}
