export function diffSummary(a: any, b: any) {
    const changes: string[] = []

    const walk = (obj1: any, obj2: any, path = '') => {
        for (const key in obj1) {
            const newPath = path ? `${path}.${key}` : key

            if (!(key in obj2)) {
                changes.push(`Removed: ${newPath}`)
            } else if (JSON.stringify(obj1[key]) !== JSON.stringify(obj2[key])) {
                if (typeof obj1[key] === 'object') {
                    walk(obj1[key], obj2[key], newPath)
                } else {
                    changes.push(`Changed: ${newPath}`)
                }
            }
        }

        for (const key in obj2) {
            if (!(key in obj1)) {
                changes.push(`Added: ${path ? `${path}.${key}` : key}`)
            }
        }
    }

    walk(a, b)
    return changes
}
