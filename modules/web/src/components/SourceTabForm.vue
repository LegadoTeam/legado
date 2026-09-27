<template>
  <el-tabs id="source-edit" v-model="activeTab">
    <el-tab-pane
      v-for="{ name, children } in Object.values(config)"
      :label="name"
      :name="name"
      :key="name"
    >
      <el-form
        v-if="activeTab === name"
        label-position="right"
        label-width="140px"
      >
        <el-form-item
          v-for="{
            type,
            title,
            namespace,
            id,
            array,
            hint,
            required = false,
          } in children"
          :label="title"
          :key="title"
          :required="required"
        >
          <el-input
            v-if="type === 'String'"
            type="textarea"
            :model-value="getFieldValue(id, namespace)"
            @update:model-value="setFieldValue(id, namespace, $event)"
            :placeholder="hint"
            autosize
          />

          <el-switch
            v-if="type === 'Boolean'"
            :model-value="getFieldValue(id, namespace)"
            @update:model-value="setFieldValue(id, namespace, $event)"
          />

          <el-input-number
            v-if="type === 'Number'"
            :model-value="getFieldValue(id, namespace)"
            @update:model-value="setFieldValue(id, namespace, $event)"
            :min="0"
          />

          <el-select
            v-if="type === 'Array'"
            :model-value="getFieldValue(id, namespace)"
            @update:model-value="setFieldValue(id, namespace, $event)"
          >
            <el-option
              v-for="(optionName, index) in array"
              :value="index"
              :key="optionName"
              :label="optionName"
            />
          </el-select>
        </el-form-item>
      </el-form>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import type { SourceConfig } from '@/config/sourceConfig'

const store = useSourceStore()
const props = defineProps<{ config: SourceConfig }>()
const activeTab = ref(Object.values(props.config)[0]?.name ?? '')

const currentSource = computed(() => store.currentSource)

const getFieldValue = (id: string, namespace?: string) => {
  const source = currentSource.value as Record<string, any>
  return namespace ? source[namespace]?.[id] : source[id]
}

const setFieldValue = (
  id: string,
  namespace: string | undefined,
  value: any,
) => {
  const source = currentSource.value as Record<string, any>
  if (!namespace) {
    source[id] = value
    return
  }
  source[namespace] ||= {}
  source[namespace][id] = value
}
/* 
修改currentSource的属性 没有直接修改本身
const { currentSource } = storeToRefs(store);
 */
</script>

<style lang="scss" scoped>
#source-edit {
  height: 100%;
  display: flex;
  flex-direction: column;
}
:deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}
:deep(.el-tab-pane) {
  height: 100%;
  box-sizing: border-box;
  padding-top: 15px;
  padding-right: 5px;
  overflow-y: auto;
}
:deep(.el-tabs__header) {
  margin: 0;
}
:deep(.el-form-item__label) {
  height: auto;
  line-height: 20px;
  white-space: normal;
  overflow-wrap: anywhere;
}

@media screen and (max-width: 600px) {
  :deep(.el-form-item) {
    display: block;
  }

  :deep(.el-form-item__label) {
    width: auto !important;
    justify-content: flex-start;
    margin-bottom: 6px;
  }

  :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }
}
</style>
