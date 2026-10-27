<!--
  PbxUserSelect: picks PBX users (address book entries) for a `targets` / `target`
  operation parameter. Split out of PropertyFormInput so that component stays a generic
  property renderer.

  v-model is the selected value(s): a string[] when the property is an array, otherwise a string.
-->
<template>
  <v-select
    :id="`pbx-user-select-${property.name}`"
    v-model="selected"
    :items="options"
    item-title="label"
    item-value="extensionNumber"
    :label="property.name"
    :hint="property.description"
    :multiple="isMultiple"
    :chips="isMultiple"
    :loading="loading"
    :rules="rules"
    no-data-text="No PBX users found"
    clearable
    persistent-hint
    variant="solo"
    density="compact"
  >
    <template #chip="{ item }">
      <v-chip color="primary">
        <v-icon icon="mdi-account" start />
        {{ item.raw.label }}
      </v-chip>
    </template>
  </v-select>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import type { Property } from '../../api/generated/property-service'
import { useUserAddressBook } from '../../utils/useUserAddressBook'

/** The parameter value: several users for an array property, one for a string property. */
type PbxUserValue = string[] | string | null

const props = defineProps<{
  modelValue: PbxUserValue
  /** The `targets` / `target` property definition from circuitProperties. */
  property: Property
}>()

const emit = defineEmits<{
  'update:modelValue': [value: PbxUserValue]
}>()

const { directory: options, loading } = useUserAddressBook()

const isMultiple = computed(() => props.property.type === 'array')

/** Ids of the users that can be picked. */
const knownIds = computed(() => new Set(options.value.map((o) => o.extensionNumber)))

/**
 * Only real users are shown as selected. Template placeholders such as
 * "$USER_ADDRESSBOOK_ID" are hidden, so the field starts empty.
 */
const selected = computed<PbxUserValue>({
  get() {
    const value = props.modelValue
    if (Array.isArray(value)) return value.filter((v) => knownIds.value.has(v))
    return value && knownIds.value.has(value) ? value : isMultiple.value ? [] : null
  },
  set(value) {
    emit('update:modelValue', value ?? (isMultiple.value ? [] : null))
  },
})

/** Required unless the template marks the property optional. */
const rules = computed(() =>
  props.property.optional
    ? []
    : [
        (v: PbxUserValue) =>
          (Array.isArray(v) ? v.length > 0 : !!v) ||
          `Select at least one ${props.property.name ?? 'user'}`,
      ],
)
</script>
