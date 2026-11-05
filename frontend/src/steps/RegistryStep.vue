<script setup lang="ts">
import { ref } from 'vue'
import { useWizard } from '../stores/wizard'
import ChoiceCard from '../components/ui/ChoiceCard.vue'
import Icon from '../components/ui/Icon.vue'

const w = useWizard()
const copied = ref(false)

const NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/

async function copyId() {
  if (!w.createdBucket) return
  try {
    await navigator.clipboard.writeText(w.createdBucket.id)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    /* clipboard may be blocked on http:// origins — the ID is still selectable */
  }
}
</script>

<template>
  <div v-if="w.loading.registry" class="muted row"><Icon name="loader" /> Reading registry clients and buckets…</div>

  <div v-else class="stack" style="--gap: 22px">
    <div class="grid-auto">
      <div class="field">
        <label for="regClient">Registry client</label>
        <select id="regClient" v-model="w.registryClientId" class="input">
          <option v-for="c in w.registryClients" :key="c.id" :value="c.id">{{ c.name }} ({{ c.id.slice(0, 8) }}…)</option>
        </select>
        <span class="muted small">From /nifi-api/controller/registry-clients</span>
      </div>
    </div>

    <div v-if="w.expectedBucketFound === false && !w.createdBucket" class="alert alert-warn" role="status">
      <Icon name="warning" :size="20" style="color: var(--warn-icon); flex-shrink: 0" />
      <div>
        <strong>Bucket “{{ w.env?.defaultBucket }}” not found in the Registry</strong>
        <div style="font-size: 13px; margin-top: 4px">
          Create it now and its ID is picked up automatically, or pick an existing bucket.
        </div>
      </div>
    </div>

    <div class="grid-auto">
      <ChoiceCard :selected="w.bucketMode === 'create'" @select="w.bucketMode = 'create'">
        <div style="font-weight: 600; font-size: 15px">Create new bucket</div>
        <div class="muted" style="font-size: 13px; margin-top: 4px">POST /nifi-registry-api/buckets</div>
      </ChoiceCard>
      <ChoiceCard
        :selected="w.bucketMode === 'existing'"
        :disabled="!w.buckets.length"
        @select="w.bucketMode = 'existing'"
      >
        <div style="font-weight: 600; font-size: 15px">Use existing bucket</div>
        <div class="muted" style="font-size: 13px; margin-top: 4px">{{ w.buckets.length }} buckets in this Registry</div>
      </ChoiceCard>
    </div>

    <!-- create -->
    <div v-if="w.bucketMode === 'create'" class="panel stack">
      <div class="grid-auto" style="--min: 240px">
        <div class="field">
          <label for="bname">Bucket name</label>
          <input id="bname" v-model="w.newBucketName" class="input" :disabled="!!w.createdBucket" />
        </div>
        <div class="field">
          <label for="bdesc">Description</label>
          <input id="bdesc" v-model="w.newBucketDesc" class="input" :disabled="!!w.createdBucket" />
        </div>
      </div>

      <div v-if="!w.createdBucket" class="row">
        <button
          class="btn btn-primary"
          type="button"
          :disabled="w.loading.bucket || !NAME_RE.test(w.newBucketName)"
          @click="w.createBucket()"
        >
          <Icon v-if="w.loading.bucket" name="loader" :size="16" />
          {{ w.loading.bucket ? 'Creating…' : 'Create bucket' }}
        </button>
        <span class="mono small muted">Reuses the bucket if one with this name already exists</span>
      </div>

      <div v-else class="alert alert-ok stack" style="--gap: 10px">
        <div class="row" style="color: var(--ok-fg); font-weight: 600; gap: 8px">
          <Icon name="check" /> Bucket ready
        </div>
        <div class="row">
          <span class="small muted" style="width: 140px">REGISTRY_BUCKET_ID</span>
          <code class="id">{{ w.createdBucket.id }}</code>
          <button class="btn btn-sm" type="button" @click="copyId">
            <Icon name="copy" :size="14" /> {{ copied ? 'Copied' : 'Copy' }}
          </button>
        </div>
        <div class="small muted">Passed to the deployment job as REGISTRY_BUCKET_ID</div>
      </div>
    </div>

    <!-- existing -->
    <fieldset v-else class="panel stack" style="--gap: 8px">
      <legend class="sr-only">Select a bucket</legend>
      <div style="font-size: 13px; font-weight: 500; margin-bottom: 4px">Select a bucket</div>
      <label v-for="b in w.buckets" :key="b.id" class="bucket" :class="{ on: b.id === w.selectedBucketId }">
        <input v-model="w.selectedBucketId" type="radio" name="bucket" :value="b.id" />
        <span class="spacer" style="font-weight: 500">{{ b.name }}</span>
        <span class="mono small muted">{{ b.id }}</span>
        <span class="small muted count">{{ b.flowCount }} flows</span>
      </label>
    </fieldset>
  </div>
</template>

<style scoped>
.id {
  font-size: 14px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  overflow-wrap: anywhere;
}
fieldset {
  margin: 0;
  min-width: 0;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
.bucket {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  min-height: 48px;
  padding: 10px 14px;
  border-radius: 8px;
  border: 1px solid var(--border);
  cursor: pointer;
}
.bucket.on {
  background: var(--surface-selected);
  border-color: var(--accent);
}
.bucket input {
  accent-color: var(--accent);
  width: 16px;
  height: 16px;
}
.count {
  width: 64px;
  text-align: right;
}
</style>
