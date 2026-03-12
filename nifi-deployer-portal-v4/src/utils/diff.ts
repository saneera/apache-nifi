import * as jsondiffpatch from "jsondiffpatch"

const diffpatch = jsondiffpatch.create({
    objectHash: function (obj:any) {
        return obj.id || obj.name
    }
})

export function calculateDiff(local:any, registry:any) {
    return diffpatch.diff(registry, local)
}

export function formatDiff(diff:any) {
    return JSON.stringify(diff, null, 2)
}
