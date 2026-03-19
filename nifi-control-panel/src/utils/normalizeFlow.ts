export function normalizeFlow(json: any) {
    return removeNoise(json)
}

function removeNoise(obj: any): any {
    if (Array.isArray(obj)) {
        return obj.map(removeNoise)
    }

    if (obj && typeof obj === 'object') {
        const cleaned: any = {}

        for (const key in obj) {
            // ❌ ignore noisy fields
            if (
                key === 'position' ||
                key === 'id' ||
                key === 'identifier' ||
                key === 'uri' ||
                key === 'timestamp'
            ) {
                continue
            }

            cleaned[key] = removeNoise(obj[key])
        }

        return cleaned
    }

    return obj
}
