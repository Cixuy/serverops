<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { getUserList, createUser, updateUser, deleteUser } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { ElMessage } from '@/utils/message'
import AppSpinner from '@/components/common/AppSpinner.vue'

const userStore = useUserStore()

const loading = ref(false)
const list = ref([])
const keyword = ref('')
const roleFilter = ref('')
const statusFilter = ref('')

const MIN_DELAY = 500
function withMinDelay(promise) {
  return Promise.all([promise, new Promise((r) => setTimeout(r, MIN_DELAY))]).then(
    ([data]) => data
  )
}

async function fetchList() {
  if (loading.value) return
  loading.value = true
  try {
    const data = await withMinDelay(getUserList())
    list.value = Array.isArray(data) ? data : []
  } finally {
    loading.value = false
  }
}

const filteredList = computed(() => {
  return list.value.filter((u) => {
    if (keyword.value.trim()) {
      const k = keyword.value.trim().toLowerCase()
      const hit =
        (u.username || '').toLowerCase().includes(k) ||
        (u.nickname || '').toLowerCase().includes(k) ||
        (u.email || '').toLowerCase().includes(k) ||
        (u.phone || '').includes(k)
      if (!hit) return false
    }
    if (roleFilter.value && u.role !== roleFilter.value) return false
    if (statusFilter.value !== '' && u.status !== Number(statusFilter.value)) return false
    return true
  })
})

const adminCount = computed(() => list.value.filter((u) => u.role === 'ADMIN').length)
const operatorCount = computed(() => list.value.filter((u) => u.role === 'OPERATOR').length)

/* ===== 新增/编辑 ===== */
const dialogVisible = ref(false)
const editingUser = ref(null)
const saving = ref(false)
const form = reactive({
  username: '',
  password: '',
  nickname: '',
  role: 'OPERATOR',
  email: '',
  phone: '',
})
const formErrors = reactive({})

function openCreate() {
  editingUser.value = null
  Object.assign(form, {
    username: '',
    password: '',
    nickname: '',
    role: 'OPERATOR',
    email: '',
    phone: '',
  })
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  dialogVisible.value = true
}

function openEdit(user) {
  editingUser.value = user
  Object.assign(form, {
    username: user.username,
    password: '',
    nickname: user.nickname,
    role: user.role,
    email: user.email || '',
    phone: user.phone || '',
  })
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  dialogVisible.value = true
}

function validate() {
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  if (!editingUser.value) {
    if (!form.username.trim()) formErrors.username = '请输入用户名'
    if (!form.password) formErrors.password = '请输入密码'
  }
  if (!form.nickname.trim()) formErrors.nickname = '请输入昵称'
  if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) formErrors.email = '邮箱格式不正确'
  if (form.phone && !/^1\d{10}$/.test(form.phone)) formErrors.phone = '手机号格式不正确'
  return Object.keys(formErrors).length === 0
}

async function handleSave() {
  if (!validate()) return
  saving.value = true
  try {
    if (editingUser.value) {
      const payload = {
        nickname: form.nickname.trim(),
        role: form.role,
        email: form.email || undefined,
        phone: form.phone || undefined,
      }
      if (form.password) payload.password = form.password
      await updateUser(editingUser.value.id, payload)
      ElMessage.success('用户更新成功')
    } else {
      await createUser({
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim(),
        role: form.role,
        email: form.email || undefined,
        phone: form.phone || undefined,
      })
      ElMessage.success('用户创建成功')
    }
    dialogVisible.value = false
    fetchList()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

/* ===== 启用/禁用 ===== */
const togglingId = ref(null)

async function toggleStatus(user) {
  togglingId.value = user.id
  try {
    await updateUser(user.id, { status: user.status === 1 ? 0 : 1 })
    ElMessage.success(`用户 ${user.username} 已${user.status === 1 ? '禁用' : '启用'}`)
    fetchList()
  } catch {
    /* 拦截器已提示 */
  } finally {
    togglingId.value = null
  }
}

/* ===== 删除 ===== */
const deleteTarget = ref(null)
const deleting = ref(false)

function askDelete(user) {
  deleteTarget.value = user
}

async function confirmDelete() {
  deleting.value = true
  try {
    await deleteUser(deleteTarget.value.id)
    ElMessage.success(`用户 ${deleteTarget.value.username} 已删除`)
    deleteTarget.value = null
    fetchList()
  } catch {
    /* 拦截器已提示 */
  } finally {
    deleting.value = false
  }
}

const avatarColors = [
  'linear-gradient(135deg, #409EFF, #B37FEB)',
  'linear-gradient(135deg, #67C23A, #95DE64)',
  'linear-gradient(135deg, #E6A23C, #FFC069)',
  'linear-gradient(135deg, #F56C6C, #FF9E9E)',
  'linear-gradient(135deg, #36CFC9, #5CDBD3)',
  'linear-gradient(135deg, #8E5CF7, #B37FEB)',
]

function avatarColor(u) {
  return avatarColors[(u.id || 0) % avatarColors.length]
}

function fmtDate(dt) {
  if (!dt) return '-'
  return String(dt).slice(0, 10)
}

onMounted(fetchList)
</script>

<template>
  <div class="user-manage">
    <div class="page-header">
      <div class="page-title">用户管理</div>
      <div style="display: flex; gap: 10px">
        <button class="btn btn-primary" @click="openCreate">
          <svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
          添加用户
        </button>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <div class="filter-item">
        <span class="filter-label">搜索</span>
        <input v-model="keyword" class="filter-input" placeholder="用户名/昵称/邮箱/手机" />
      </div>
      <div class="filter-item">
        <span class="filter-label">角色</span>
        <select v-model="roleFilter" class="filter-select">
          <option value="">全部</option>
          <option value="ADMIN">管理员</option>
          <option value="OPERATOR">操作员</option>
        </select>
      </div>
      <div class="filter-item">
        <span class="filter-label">状态</span>
        <select v-model="statusFilter" class="filter-select">
          <option value="">全部</option>
          <option :value="1">启用</option>
          <option :value="0">禁用</option>
        </select>
      </div>
      <button class="btn btn-default btn-sm" :disabled="loading" @click="fetchList">
        <AppSpinner v-if="loading" :size="14" />
        {{ loading ? '刷新中...' : '刷新' }}
      </button>
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <div class="table-toolbar">
        <h3>系统用户（{{ filteredList.length }} 人）</h3>
        <span class="toolbar-tip">管理员 {{ adminCount }} / 操作员 {{ operatorCount }}</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>用户</th>
              <th>角色</th>
              <th>邮箱</th>
              <th>手机</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in filteredList" :key="u.id">
              <td>
                <div class="user-cell">
                  <div class="avatar" :style="{ background: avatarColor(u) }">
                    {{ (u.nickname || u.username || '?').charAt(0).toUpperCase() }}
                  </div>
                  <div>
                    <div class="username">{{ u.username }}</div>
                    <div class="nickname">{{ u.nickname }}</div>
                  </div>
                </div>
              </td>
              <td>
                <span class="tag" :class="u.role === 'ADMIN' ? 'tag-admin' : 'tag-operator'">
                  {{ u.role === 'ADMIN' ? '管理员' : '操作员' }}
                </span>
              </td>
              <td class="dim">{{ u.email || '-' }}</td>
              <td class="dim">{{ u.phone || '-' }}</td>
              <td>
                <span class="tag" :class="u.status === 1 ? 'tag-success' : 'tag-danger'">
                  {{ u.status === 1 ? '启用' : '禁用' }}
                </span>
              </td>
              <td class="time">{{ fmtDate(u.createdAt) }}</td>
              <td>
                <button class="btn-text primary" @click="openEdit(u)">编辑</button>
                <button
                  v-if="u.id !== userStore.userInfo?.id"
                  class="btn-text"
                  :class="u.status === 1 ? 'warning' : 'primary'"
                  :disabled="togglingId === u.id"
                  @click="toggleStatus(u)"
                >
                  {{ u.status === 1 ? '禁用' : '启用' }}
                </button>
                <button
                  v-if="u.id !== userStore.userInfo?.id"
                  class="btn-text danger"
                  @click="askDelete(u)"
                >
                  删除
                </button>
              </td>
            </tr>
            <tr v-if="!filteredList.length && !loading">
              <td colspan="7" class="empty-cell">暂无匹配的用户数据</td>
            </tr>
          </tbody>
        </table>
        <div v-if="loading" class="table-loading-mask">
          <AppSpinner :size="26" />
          <span>数据加载中...</span>
        </div>
      </div>
    </div>

    <!-- 新增/编辑弹窗 -->
    <!-- 取消点击遮罩关闭：避免填写内容时误点外部导致输入丢失，仅通过 × 或取消按钮关闭 -->
    <div v-if="dialogVisible" class="modal-mask">
      <div class="modal-box">
        <div class="modal-header">
          <h3>{{ editingUser ? '编辑用户' : '添加用户' }}</h3>
          <button class="modal-close" @click="dialogVisible = false">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          <div v-if="!editingUser" class="form-row">
            <div class="form-item">
              <label>用户名<span class="required">*</span></label>
              <input v-model="form.username" class="form-input" placeholder="登录账号" />
              <div v-if="formErrors.username" class="error-tip">{{ formErrors.username }}</div>
            </div>
            <div class="form-item">
              <label>密码<span class="required">*</span></label>
              <input
                v-model="form.password"
                class="form-input"
                type="password"
                placeholder="登录密码"
                autocomplete="new-password"
              />
              <div v-if="formErrors.password" class="error-tip">{{ formErrors.password }}</div>
            </div>
          </div>
          <div class="form-row">
            <div class="form-item">
              <label>昵称<span class="required">*</span></label>
              <input v-model="form.nickname" class="form-input" placeholder="显示名称" />
              <div v-if="formErrors.nickname" class="error-tip">{{ formErrors.nickname }}</div>
            </div>
            <div class="form-item">
              <label>角色<span class="required">*</span></label>
              <select v-model="form.role" class="form-select">
                <option value="OPERATOR">操作员</option>
                <option value="ADMIN">管理员</option>
              </select>
            </div>
          </div>
          <div class="form-row">
            <div class="form-item">
              <label>邮箱</label>
              <input v-model="form.email" class="form-input" placeholder="选填" />
              <div v-if="formErrors.email" class="error-tip">{{ formErrors.email }}</div>
            </div>
            <div class="form-item">
              <label>手机号</label>
              <input v-model="form.phone" class="form-input" placeholder="选填" />
              <div v-if="formErrors.phone" class="error-tip">{{ formErrors.phone }}</div>
            </div>
          </div>
          <div v-if="editingUser" class="form-item">
            <label>重置密码</label>
            <input
              v-model="form.password"
              class="form-input"
              type="password"
              placeholder="留空则不修改密码"
              autocomplete="new-password"
            />
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="dialogVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="saving" @click="handleSave">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteTarget" class="modal-mask" @click.self="deleteTarget = null">
      <div class="modal-box confirm-box">
        <div class="modal-header">
          <h3>删除确认</h3>
          <button class="modal-close" @click="deleteTarget = null">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          确定删除用户 <strong>{{ deleteTarget.username }}（{{ deleteTarget.nickname }}）</strong> 吗？该操作不可恢复。
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="deleteTarget = null">取消</button>
          <button class="btn btn-danger" :disabled="deleting" @click="confirmDelete">
            {{ deleting ? '删除中...' : '确定删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-title { font-size: 20px; font-weight: 600; }

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
.btn-primary:hover:not(:disabled) { background: #66b1ff; border-color: #66b1ff; }
.btn-default { background: var(--card-bg); border-color: var(--input-border); color: var(--text-regular); }
.btn-default:hover:not(:disabled) { color: #409eff; border-color: #c6e2ff; background: #ecf5ff; }
.btn-danger { background: #f56c6c; border-color: #f56c6c; color: #fff; }
.btn-danger:hover { background: #f78989; border-color: #f78989; }
.btn-danger:disabled, .btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-default:disabled { opacity: 0.6; cursor: not-allowed; }
.btn svg { width: 14px; height: 14px; fill: currentColor; }

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
.filter-item { display: flex; align-items: center; gap: 8px; }
.filter-label { font-size: 13px; color: var(--text-secondary); }
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
}
.filter-input:focus, .filter-select:focus { border-color: #409eff; }
.filter-input { width: 220px; }

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
.table-toolbar h3 { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.toolbar-tip { font-size: 12px; color: var(--text-secondary); }

.table-wrap { position: relative; }
.table-loading-mask {
  position: absolute;
  inset: 0;
  background: var(--mask-bg);
  backdrop-filter: blur(1px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  z-index: 5;
  font-size: 13px;
  color: #409eff;
}

table { width: 100%; border-collapse: collapse; }
thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 10px 16px;
  background: var(--row-hover-bg);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody td {
  padding: 14px 16px;
  font-size: 13px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody tr:hover { background: var(--row-hover-bg); }
tbody tr:last-child td { border-bottom: none; }
.empty-cell { text-align: center; color: var(--text-secondary); padding: 40px !important; white-space: normal !important; }

.tag {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}
.tag-admin { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; }
.tag-operator { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }
.tag-success { background: #f0f9eb; color: #67c23a; border: 1px solid #e1f3d8; }
.tag-danger { background: #fef0f0; color: #f56c6c; border: 1px solid #fde2e2; }

.user-cell { display: flex; align-items: center; gap: 10px; }
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}
.username { font-weight: 500; }
.nickname { font-size: 12px; color: var(--text-secondary); margin-top: 1px; }

.dim { color: var(--text-regular); }
.time { color: var(--text-secondary); font-size: 12px; }

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
.btn-text.danger { color: #f56c6c; }
.btn-text.danger:hover { background: #fef0f0; }
.btn-text.warning { color: #e6a23c; }
.btn-text.warning:hover { background: #fdf6ec; }
.btn-text:disabled { opacity: 0.5; cursor: not-allowed; }

.error-tip {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 4px;
}
.confirm-box { width: 440px; }
.modal-body { font-size: 14px; color: var(--text-regular); line-height: 1.7; }
</style>
