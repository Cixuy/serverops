<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from '@/utils/message'
import AppPagination from '@/components/common/AppPagination.vue'
import {
  getMonitorConfigList,
  createMonitorConfig,
  updateMonitorConfig,
  deleteMonitorConfig,
  updateMonitorConfigStatus,
  getServerList,
  getServiceHealingBindings,
  createServiceHealingBinding,
  deleteServiceHealingBinding,
  toggleServiceHealingBindingStatus,
  getHealingRules,
} from '@/api'

/* ============================================================
 * 服务配置页面
 * 依据原型设计（服务监控 / 服务配置）落地为 Vue 组件。
 * 对接后端 monitor-configs 接口，实现 CRUD 与启停操作。
 * ============================================================ */

// ===== 服务器列表（从后端获取）=====
const ALL_SERVER = 'ALL'
const servers = ref([])
const currentServer = ref(ALL_SERVER)

// ===== 筛选与分页 =====
const query = reactive({
  keyword: '',
  status: '',
  pageNum: 1,
  pageSize: 10,
})

// ===== 表格数据 =====
const tableData = ref([])
const total = ref(0)

// 启用/禁用统计
const enabledCount = computed(() => tableData.value.filter((s) => s.status === 1).length)
const disabledCount = computed(() => tableData.value.length - enabledCount.value)

// ===== 加载数据 =====
async function loadData() {
  if (!currentServer.value) return
  const params = {
    keyword: query.keyword || undefined,
    status: query.status || undefined,
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  }
  if (currentServer.value !== ALL_SERVER) {
    params.serverCode = currentServer.value
  }
  const res = await getMonitorConfigList(params)
  const list = res.list || []
  total.value = res.total || 0

  // 先并行收集当前页每个服务已绑定的自愈规则（接口：12-服务规则绑定）
  // 再合并到服务对象并一次性赋值 tableData，确保列表首次渲染时 healingRules 已有值，
  // 彻底避免"先显示未配置 → 再刷出规则"的闪烁
  const results = await Promise.all(
    list.map(async (s) => {
      try {
        const bindings = await getServiceHealingBindings({
          serverCode: s.serverCode,
          configId: s.id,
        })
        // 列表展示所有绑定（含停用）：已停用的规则也显示但置灰，而非显示"未配置"
        return extractBindings(bindings)
          .map((b) => ({ ruleName: b.ruleName, status: b.status }))
          .filter((x) => x.ruleName)
      } catch {
        return []
      }
    }),
  )
  list.forEach((s, i) => {
    s.healingRules = results[i]
  })
  tableData.value = list
}

onMounted(async () => {
  try {
    const res = await getServerList({ pageNum: 1, pageSize: 100 })
    const list = res.list || []
    servers.value = [
      { code: ALL_SERVER, name: '全部服务器', status: 'online' },
      ...list.map((s) => ({
        code: s.serverCode,
        name: s.hostname || s.serverCode,
        status: s.status === 1 ? 'online' : 'offline',
      })),
    ]
    currentServer.value = ALL_SERVER
    loadData()
  } catch {
    servers.value = []
  }
})

// ===== 服务器切换 =====
function selectServer(code) {
  currentServer.value = code
  query.pageNum = 1
  loadData()
}

// ===== 筛选 =====
function handleSearch() {
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.status = ''
  query.pageNum = 1
  loadData()
}

function handlePageChange(p) {
  query.pageNum = p
  loadData()
}

function handleSizeChange(s) {
  query.pageSize = s
  query.pageNum = 1
  loadData()
}

// ===== 启用/禁用开关 =====
async function toggleEnabled(s) {
  const newStatus = s.status === 1 ? 0 : 1
  try {
    await updateMonitorConfigStatus(s.id, newStatus)
    s.status = newStatus
    ElMessage.success(`「${s.serviceName}」已${newStatus === 1 ? '启用' : '禁用'}监控`)
    loadData()
  } catch {
    ElMessage.error('操作失败')
  }
}

// ===== 添加 / 编辑弹窗 =====
const formVisible = ref(false)
const formTitle = ref('添加监控服务')
const editingId = ref(null)
const form = reactive({
  serverCode: '',
  serviceName: '',
  processName: '',
  port: undefined,
  checkInterval: 60,
})

function openAdd() {
  formTitle.value = '添加监控服务'
  editingId.value = null
  const defaultServer =
    currentServer.value !== ALL_SERVER ? currentServer.value : (servers.value[1]?.code || '')
  Object.assign(form, {
    serverCode: defaultServer,
    serviceName: '',
    processName: '',
    port: undefined,
    checkInterval: 60,
  })
  formVisible.value = true
}

function openEdit(s) {
  formTitle.value = '编辑监控服务'
  editingId.value = s.id
  // 编辑弹窗不展示服务器字段（服务器归属在列表中以"绑定服务器"列展示，创建后不可修改）
  Object.assign(form, {
    serverCode: s.serverCode || '',
    serviceName: s.serviceName,
    processName: s.processName || '',
    port: s.port,
    checkInterval: s.checkInterval || 60,
  })
  formVisible.value = true
}

async function submitForm() {
  // 仅添加时校验所属服务器；编辑时不展示该字段
  if (!editingId.value && !form.serverCode) {
    ElMessage.warning('请选择所属服务器')
    return
  }
  if (!form.serviceName.trim()) {
    ElMessage.warning('请输入服务名称')
    return
  }
  try {
    if (editingId.value) {
      // 更新接口仅接受 serviceName/processName/port/checkInterval，不包含 serverCode
      const { serverCode, ...updateData } = form
      await updateMonitorConfig(editingId.value, updateData)
      ElMessage.success(`「${form.serviceName}」配置已保存`)
    } else {
      await createMonitorConfig(form)
      ElMessage.success(`监控服务「${form.serviceName}」已添加`)
    }
    formVisible.value = false
    loadData()
  } catch {
    ElMessage.error('保存失败')
  }
}

// ===== 自愈规则绑定管理弹窗 =====
const bindingVisible = ref(false)
const bindingLoading = ref(false)
const bindingTarget = ref(null) // 当前管理的服务配置 { id, serverCode, serviceName }
const bindings = ref([]) // 已绑定规则列表
const allRules = ref([]) // 全部可用自愈规则
const selectedRuleId = ref('') // 下拉选中的待添加规则

// 兼容接口返回结构：接口可能返回数组或分页对象 { total, pageNum, pageSize, list }
// 统一提取出绑定列表数组
function extractBindings(res) {
  if (Array.isArray(res)) return res
  if (res && Array.isArray(res.list)) return res.list
  return []
}

// 打开绑定规则管理弹窗（从编辑监控服务弹窗进入）
async function openBindingManager(s) {
  bindingTarget.value = { id: s.id, serverCode: s.serverCode, serviceName: s.serviceName }
  selectedRuleId.value = ''
  bindingVisible.value = true
  bindingLoading.value = true
  try {
    // 并行加载：当前服务已绑定规则 + 全部可用规则
    const [b, rules] = await Promise.all([
      getServiceHealingBindings({ serverCode: s.serverCode, configId: s.id }),
      getHealingRules({ pageNum: 1, pageSize: 100 }),
    ])
    bindings.value = extractBindings(b)
    allRules.value = (rules && rules.list) || []
  } catch {
    bindings.value = []
    allRules.value = []
    ElMessage.error('加载绑定信息失败')
  } finally {
    bindingLoading.value = false
    // 默认选中第一个未绑定的规则，避免下拉为空显示
    selectedRuleId.value = unboundRules.value[0]?.id != null ? String(unboundRules.value[0].id) : ''
  }
}

// 重置待添加规则下拉：默认选中当前第一个未绑定的规则
function resetSelectedRule() {
  selectedRuleId.value = unboundRules.value[0]?.id != null ? String(unboundRules.value[0].id) : ''
}

// 尚未绑定的规则（供添加下拉选择）
// 后端返回已绑定项字段为 rule_id（规则 id），用它过滤掉已绑定的规则
const unboundRules = computed(() => {
  const boundIds = new Set(bindings.value.map((b) => b.rule_id))
  return allRules.value.filter((r) => !boundIds.has(r.id))
})

// 添加规则绑定
async function addBinding() {
  if (!selectedRuleId.value) {
    ElMessage.warning('请选择要绑定的规则')
    return
  }
  const t = bindingTarget.value
  try {
    await createServiceHealingBinding({
      serverCode: t.serverCode,
      configId: t.id,
      ruleId: Number(selectedRuleId.value),
      priority: 0,
    })
    ElMessage.success('规则绑定成功')
    // 刷新弹窗内绑定列表（列表行自愈规则列待编辑保存后统一刷新）
    const b = await getServiceHealingBindings({ serverCode: t.serverCode, configId: t.id })
    bindings.value = extractBindings(b)
    // 重新默认选中第一个未绑定规则
    resetSelectedRule()
  } catch {
    ElMessage.error('绑定失败')
  }
}

// 解绑规则（bind_id 为后端返回的绑定记录 id）
async function unbind(b) {
  try {
    await deleteServiceHealingBinding(b.bind_id)
    ElMessage.success(`已解绑规则「${b.ruleName}」`)
    const t = bindingTarget.value
    const res = await getServiceHealingBindings({ serverCode: t.serverCode, configId: t.id })
    bindings.value = extractBindings(res)
    // 列表行自愈规则列待编辑保存后统一刷新
  } catch {
    ElMessage.error('解绑失败')
  }
}

// 启停某绑定的监听状态：控制该规则对此服务是否生效（不删除绑定，仅暂停/恢复触发）
async function toggleBinding(b) {
  const newStatus = b.status === 1 ? 0 : 1
  try {
    await toggleServiceHealingBindingStatus(b.bind_id, newStatus)
    b.status = newStatus
    ElMessage.success(`规则「${b.ruleName}」已${newStatus === 1 ? '启用监听' : '停用监听'}`)
  } catch {
    ElMessage.error('操作失败')
  }
}

// ===== 删除 =====
const deleteTarget = ref(null)

function askDelete(s) {
  deleteTarget.value = s
}

async function confirmDelete() {
  try {
    await deleteMonitorConfig(deleteTarget.value.id)
    ElMessage.success(`监控服务「${deleteTarget.value.serviceName}」已删除`)
    deleteTarget.value = null
    loadData()
  } catch {
    ElMessage.error('删除失败')
  }
}

// 兼容旧模板变量
const allServices = computed(() => tableData.value)
const pagedList = computed(() => tableData.value)

// 绑定服务器列直接展示 serverCode，不再去服务器列表里映射主机名，
// 避免出现"主机名/code 混着显示"的不一致现象（改动点）
function serverNameOf(serverCode) {
  return serverCode || '-'
}

// 当前服务器展示名（下拉框选中值为 code，标题需显示可读名称）
const currentServerName = computed(() => {
  const found = servers.value.find((s) => s.code === currentServer.value)
  return found ? found.name : currentServer.value
})
</script>

<template>
  <div class="service-config">
    <!-- 页头：标题 + 操作按钮 -->
    <div class="page-header">
      <div class="page-title">服务监控配置</div>
      <div class="page-actions">
        <button class="btn btn-primary" @click="openAdd">
          <svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
          添加监控服务
        </button>
      </div>
    </div>

    <!-- 服务器选择器 -->
    <div class="server-selector">
      <label>选择服务器：</label>
      <select class="server-select" v-model="currentServer" @change="selectServer(currentServer)">
        <option v-for="s in servers" :key="s.code" :value="s.code">{{ s.name }}</option>
      </select>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <div class="filter-item">
        <span class="filter-label">服务名称</span>
        <input
          v-model="query.keyword"
          class="filter-input"
          placeholder="搜索服务名称"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="filter-item">
        <span class="filter-label">状态</span>
        <select v-model="query.status" class="filter-select" @change="handleSearch">
          <option value="">全部</option>
          <option value="1">启用</option>
          <option value="0">禁用</option>
        </select>
      </div>
      <button class="btn btn-primary btn-sm" @click="handleSearch">搜索</button>
      <button class="btn btn-default btn-sm" @click="handleReset">重置</button>
    </div>

    <!-- 服务配置表格 -->
    <div class="table-card">
      <div class="table-toolbar">
        <h3>{{ currentServerName }} 监控服务列表 ({{ total }}项)</h3>
        <span class="table-tip">启用 {{ enabledCount }} / 禁用 {{ disabledCount }}</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>服务名称</th>
              <th>绑定服务器</th>
              <th>进程名</th>
              <th>端口</th>
              <th>检测间隔(秒)</th>
              <th>自愈规则</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in pagedList" :key="s.id">
              <td><strong>{{ s.serviceName }}</strong></td>
              <td>{{ serverNameOf(s.serverCode) }}</td>
              <td>{{ s.processName || '-' }}</td>
              <td>{{ s.port || '-' }}</td>
              <td>{{ s.checkInterval || '-' }}</td>
              <td>
                <!-- 自愈规则：展示该服务已绑定的规则（含停用项置灰），无绑定则显示未配置 -->
                <div v-if="s.healingRules && s.healingRules.length" class="healing-rules-cell">
                  <!-- 监听中紫色高亮，停用置灰 -->
                  <span
                    v-for="(r, i) in s.healingRules"
                    :key="i"
                    class="tag"
                    :class="r.status === 1 ? 'tag-purple' : 'tag-gray'"
                    :title="r.status === 1 ? '监听中' : '已停用'"
                    style="font-size: 11px;"
                  >{{ r.ruleName }}</span>
                </div>
                <span v-else class="tag tag-gray" style="font-size: 11px;">未配置</span>
              </td>
              <td>
                <span class="status-dot" :class="s.status === 1 ? 'green' : 'gray'"></span>
                <span class="tag" :class="s.status === 1 ? 'tag-success' : 'tag-gray'">
                  {{ s.status === 1 ? '启用' : '禁用' }}
                </span>
              </td>
              <td>
                <button class="btn-text" :class="s.status === 1 ? 'warning' : 'primary'" @click="toggleEnabled(s)">
                  {{ s.status === 1 ? '停用' : '启用' }}
                </button>
                <button class="btn-text primary" @click="openEdit(s)">编辑</button>
                <button class="btn-text danger" @click="askDelete(s)">删除</button>
              </td>
            </tr>
            <tr v-if="!pagedList.length">
              <td colspan="8">
                <div class="empty-state">
                  <svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
                  <p>暂无符合条件的监控服务</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <AppPagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :show-size="true" @change="handlePageChange" @size-change="handleSizeChange" />
    </div>

    <!-- 添加 / 编辑弹窗 -->
    <!-- 取消点击遮罩关闭：避免填写内容时误点外部导致输入丢失，仅通过 × 或取消按钮关闭 -->
    <div v-if="formVisible" class="modal-mask">
      <div class="modal-box">
        <div class="modal-header">
          <h3>{{ formTitle }}</h3>
          <button class="modal-close" @click="formVisible = false">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          <!-- 添加监控服务时需选择所属服务器；编辑时该字段隐藏（服务器归属不可修改） -->
          <div v-if="!editingId" class="form-row">
            <div class="form-item">
              <label>所属服务器 <span class="required">*</span></label>
              <select v-model="form.serverCode" class="form-input">
                <option value="">请选择服务器</option>
                <option v-for="s in servers.filter((x) => x.code !== ALL_SERVER)" :key="s.code" :value="s.code">
                  {{ s.name }}
                </option>
              </select>
            </div>
            <div class="form-item">
              <label>服务名称 <span class="required">*</span></label>
              <input v-model="form.serviceName" class="form-input" placeholder="如：MySQL" />
            </div>
          </div>
          <!-- 编辑监控服务：不展示服务器字段，仅编辑服务信息 -->
          <div v-else class="form-row">
            <div class="form-item">
              <label>服务名称 <span class="required">*</span></label>
              <input v-model="form.serviceName" class="form-input" placeholder="如：MySQL" />
            </div>
          </div>
          <div class="form-row">
            <div class="form-item">
              <label>进程名</label>
              <input v-model="form.processName" class="form-input" placeholder="如：mysqld.exe" />
            </div>
            <div class="form-item">
              <label>端口</label>
              <input v-model.number="form.port" class="form-input" type="number" placeholder="如：3306" />
            </div>
          </div>
          <div class="form-row">
            <div class="form-item">
              <label>检测间隔（秒）</label>
              <input v-model.number="form.checkInterval" class="form-input" type="number" placeholder="如：60" />
            </div>
            <!-- 编辑态：在端口下方提供自愈规则绑定管理入口 -->
            <div v-if="editingId" class="form-item binding-field">
              <label>自愈规则</label>
              <button class="btn btn-default btn-sm" @click="openBindingManager({ id: editingId, serverCode: form.serverCode, serviceName: form.serviceName })">
                <svg viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
                编辑规则
              </button>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="formVisible = false">取消</button>
          <button class="btn btn-primary" @click="submitForm">保存</button>
        </div>
      </div>
    </div>

    <!-- 自愈规则绑定管理弹窗 -->
    <div v-if="bindingVisible" class="modal-mask" @click.self="bindingVisible = false">
      <div class="modal-box binding-box">
        <div class="modal-header">
          <h3>绑定自愈规则 - {{ bindingTarget.serviceName }}</h3>
          <button class="modal-close" @click="bindingVisible = false">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          <div v-if="bindingLoading" class="binding-loading">加载中...</div>
          <template v-else>
            <!-- 已绑定规则列表 -->
            <div class="binding-section">
              <div class="binding-label">已绑定规则</div>
              <div v-if="bindings.length" class="binding-list">
                <div v-for="b in bindings" :key="b.id" class="binding-item">
                  <!-- 监听中紫色高亮，停用置灰，视觉反馈监听状态 -->
                  <span class="tag" :class="b.status === 1 ? 'tag-purple' : 'tag-gray'">{{ b.ruleName }}</span>
                  <span class="binding-server">{{ b.serverCode }}</span>
                  <!-- 启停监听开关：控制该规则对此服务是否生效（不删除绑定） -->
                  <div class="binding-toggle" :title="b.status === 1 ? '点击停用监听' : '点击启用监听'">
                    <span class="toggle-label">{{ b.status === 1 ? '监听中' : '已停用' }}</span>
                    <span class="switch" :class="{ on: b.status === 1 }" @click="toggleBinding(b)"></span>
                  </div>
                  <button class="btn-text danger" @click="unbind(b)">解绑</button>
                </div>
              </div>
              <div v-else class="binding-empty">该服务暂未绑定任何自愈规则</div>
            </div>

            <!-- 添加规则 -->
            <div class="binding-section">
              <div class="binding-label">添加规则</div>
              <div class="binding-add">
                <!-- 下拉直接展示未绑定的规则，默认选中第一个 -->
                <select v-model="selectedRuleId" class="form-input binding-select">
                  <option v-for="r in unboundRules" :key="r.id" :value="r.id">{{ r.ruleName }}</option>
                </select>
                <button class="btn btn-primary" :disabled="!selectedRuleId" @click="addBinding">添加</button>
              </div>
              <div v-if="!unboundRules.length" class="binding-hint">所有规则已绑定，无可添加项</div>
            </div>
          </template>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="bindingVisible = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="deleteTarget" class="modal-mask" @click.self="deleteTarget = null">
      <div class="modal-box confirm-box">
        <div class="modal-header">
          <h3>删除确认</h3>
          <button class="modal-close" @click="deleteTarget = null">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          确定删除监控服务
          <strong>{{ deleteTarget.serviceName }}</strong>
          吗？删除后该服务的监控与自愈配置将一并移除。
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="deleteTarget = null">取消</button>
          <button class="btn btn-danger" @click="confirmDelete">确定删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ===== 页头 ===== */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
}
.page-actions {
  display: flex;
  gap: 10px;
}

/* ===== 按钮（沿用项目风格）===== */
.btn {
  padding: 9px 18px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid;
  transition: all 0.2s;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: inherit;
}
.btn-sm { padding: 7px 16px; }
.btn-primary { background: #409eff; border-color: #409eff; color: #fff; }
.btn-primary:hover { background: #66b1ff; border-color: #66b1ff; }
.btn-default { background: var(--card-bg); border-color: var(--input-border); color: var(--text-regular); }
.btn-default:hover { color: #409eff; border-color: #c6e2ff; background: #ecf5ff; }
.btn-danger { background: #f56c6c; border-color: #f56c6c; color: #fff; }
.btn-danger:hover { background: #f78989; border-color: #f78989; }
.btn svg { width: 14px; height: 14px; fill: currentColor; }

/* ===== 服务器选择器 ===== */
.server-selector {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.server-selector label {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.server-select {
  padding: 7px 12px;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  outline: none;
  background: var(--input-bg);
  font-family: inherit;
  min-width: 200px;
  transition: border-color 0.2s;
}
.server-select:focus { border-color: #409eff; }

/* ===== 筛选栏 ===== */
.filter-bar {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 14px 20px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-label {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.filter-input,
.filter-select {
  padding: 7px 12px;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  outline: none;
  background: var(--input-bg);
  font-family: inherit;
  transition: border-color 0.2s;
}
.filter-input:focus,
.filter-select:focus { border-color: #409eff; }
.filter-input { width: 180px; }
.filter-select { width: 130px; }

/* ===== 表格 ===== */
.table-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  overflow: hidden;
}
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--card-border);
}
.table-toolbar h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.table-tip {
  font-size: 12px;
  color: var(--text-secondary);
}
.table-wrap { overflow-x: auto; }

table { width: 100%; border-collapse: collapse; }
thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 10px 16px;
  background: var(--table-head-bg);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody td {
  padding: 12px 16px;
  font-size: 13px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody tr:hover { background: var(--row-hover-bg); }
tbody tr:last-child td { border-bottom: none; }

/* ===== 标签 ===== */
.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}
.tag-success { background: #f0f9eb; color: #67c23a; border: 1px solid #e1f3d8; }
.tag-danger { background: #fef0f0; color: #f56c6c; border: 1px solid #fde2e2; }
.tag-warning { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; }
.tag-info { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }
.tag-gray { background: #f4f4f5; color: #909399; border: 1px solid #e9e9eb; }
.tag-purple { background: #f5f0ff; color: #8e5cf7; border: 1px solid #e4d8ff; }

/* 自愈规则单元格：多个规则标签可换行展示 */
.healing-rules-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  min-width: 80px;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}
.status-dot.green { background: #67c23a; box-shadow: 0 0 4px rgba(103, 194, 58, 0.4); }
.status-dot.gray { background: #c0c4cc; }

/* ===== 行内文本 ===== */
.mono { font-family: Consolas, Monaco, monospace; }

/* ===== 文本按钮 ===== */
.btn-text {
  background: none;
  border: none;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: all 0.2s;
  font-family: inherit;
}
.btn-text.primary { color: #409eff; }
.btn-text.primary:hover { background: #ecf5ff; }
.btn-text.warning { color: #e6a23c; }
.btn-text.warning:hover { background: #fdf6ec; }
.btn-text.danger { color: #f56c6c; }
.btn-text.danger:hover { background: #fef0f0; }

/* ===== 空状态 ===== */
.empty-state { text-align: center; padding: 50px 20px; }
.empty-state svg { width: 64px; height: 64px; fill: var(--text-disabled); margin-bottom: 16px; }
.empty-state p { font-size: 14px; color: var(--text-secondary); }

/* ===== 弹窗表单 ===== */
.confirm-box { width: 440px; }
.switch-item {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 0;
}
.switch-label {
  font-size: 13px;
  color: var(--text-regular);
  font-weight: 500;
}
.switch {
  position: relative;
  width: 36px;
  height: 20px;
  background: #c0c4cc;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.3s;
  flex-shrink: 0;
}
.switch.on { background: #409eff; }
.switch::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  background: #fff;
  border-radius: 50%;
  transition: transform 0.3s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}
.switch.on::after { transform: translateX(16px); }

/* ===== 自愈规则绑定管理弹窗 ===== */
.binding-box { width: 520px; }
.binding-loading { text-align: center; color: var(--text-secondary); padding: 30px 0; }
.binding-section { margin-bottom: 20px; }
.binding-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-regular);
  margin-bottom: 10px;
}
.binding-list {
  border: 1px solid var(--card-border);
  border-radius: 8px;
  padding: 6px 12px;
}
.binding-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--card-border);
}
/* 启停监听开关：标签 + 开关 */
.binding-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.toggle-label {
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.binding-item:last-child { border-bottom: none; }
.binding-server {
  font-size: 11px;
  color: var(--text-secondary);
  font-family: Consolas, Monaco, monospace;
  flex: 1;
}
.binding-empty {
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--row-hover-bg);
  border: 1px dashed var(--card-border);
  border-radius: 8px;
  padding: 16px;
  text-align: center;
}
.binding-add {
  display: flex;
  gap: 10px;
}
.binding-select { flex: 1; }
.binding-hint {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 8px;
}
/* 编辑弹窗右下角的"编辑规则"按钮 */
.modal-footer .btn svg { width: 13px; height: 13px; }
</style>
