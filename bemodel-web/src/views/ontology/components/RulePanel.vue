<template>
  <el-card>
    <div class="filter-bar">
      <el-select
        v-model="filterConcept"
        placeholder="按概念筛选"
        clearable
        filterable
        style="width: 240px"
        @change="loadRules"
      >
        <el-option
          v-for="c in conceptStore.concepts"
          :key="c.code"
          :label="`${c.name}（${c.code}）`"
          :value="c.code"
        />
      </el-select>
      <el-button type="primary" class="filter-bar-action" @click="openDialog('create')">新建规则</el-button>
    </div>

    <!-- 分类页签（带数量徽标） -->
    <div class="rule-tabs">
      <div
        v-for="t in typeTabs"
        :key="t.value"
        class="rule-tab"
        :class="{ active: filterType === t.value }"
        @click="filterType = t.value"
      >
        {{ t.label }} <span class="tab-count">{{ t.count }}</span>
      </div>
    </div>

    <el-table :data="filteredRules" v-loading="loading">
      <el-table-column prop="ruleCode" label="规则编码" width="140" />
      <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="conceptCode" label="关联概念" width="120" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="ruleTypeTag(row.ruleType)">{{ row.ruleType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="严重度" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="severityTag(row.severity)">{{ row.severity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关联指标" width="150">
        <template #default="{ row }">{{ row.metricCode || '-' }}</template>
      </el-table-column>
      <el-table-column label="驱动方式" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.engine === 'QC'" size="small" type="success">表达式驱动</el-tag>
          <el-tag v-else-if="row.metricCode" size="small" type="info">监控指标</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="表达式" width="80">
        <template #default="{ row }">
          <el-button v-if="row.exprJson" link type="primary" size="small" @click="openDetail(row, 'code')">
            查看
          </el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="owner" label="负责人" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="70">
        <template #default="{ row }">v{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <el-button
            v-if="row.status === 'DRAFT'"
            link type="primary" size="small"
            :loading="actingCode === row.ruleCode"
            @click="doTransition(row, 'REVIEW')"
          >提交评审</el-button>
          <template v-if="row.status === 'REVIEW'">
            <el-button
              link type="success" size="small"
              :loading="actingCode === row.ruleCode"
              @click="doTransition(row, 'PUBLISHED')"
            >发布</el-button>
            <el-button
              link type="warning" size="small"
              :loading="actingCode === row.ruleCode"
              @click="doTransition(row, 'DRAFT')"
            >退回草稿</el-button>
          </template>
          <el-button
            v-if="row.status === 'PUBLISHED'"
            link type="danger" size="small"
            :loading="actingCode === row.ruleCode"
            @click="doTransition(row, 'DEPRECATED')"
          >废弃</el-button>
          <el-button
            v-if="row.status === 'DEPRECATED'"
            link type="primary" size="small"
            :loading="actingCode === row.ruleCode"
            @click="doTransition(row, 'DRAFT')"
          >重建为草稿</el-button>
          <el-button link size="small" @click="openDialog('edit', row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 规则详情抽屉：双模态规则定义 -->
    <el-drawer v-model="detailVisible" size="560px" :with-header="false">
      <template v-if="detailRule">
        <div class="rd-header">
          <div>
            <div class="rd-name">{{ detailRule.name }}</div>
            <div class="rd-meta">{{ detailRule.ruleCode }} · v{{ detailRule.version }}</div>
          </div>
          <div class="rd-header-side">
            <el-tag size="small" :type="statusTagType(detailRule.status)">{{ statusText(detailRule.status) }}</el-tag>
            <el-tag size="small" :type="severityTag(detailRule.severity)">{{ detailRule.severity }}</el-tag>
            <el-icon class="rd-close" @click="detailVisible = false"><Close /></el-icon>
          </div>
        </div>

        <div class="rd-section-title">规则描述</div>
        <p class="rd-desc">{{ detailRule.expression || '暂无业务语言描述' }}</p>

        <div class="rd-section-head">
          <div class="rd-section-title rd-section-title-flat">规则定义</div>
          <el-segmented
            v-if="hasExpr"
            v-model="detailMode"
            size="small"
            :options="[
              { label: '可视化', value: 'visual' },
              { label: '规则代码', value: 'code' }
            ]"
          />
        </div>

        <template v-if="detailMode === 'visual'">
          <div v-if="detailTypeMeta" class="rd-type-line">
            <el-tag size="small" type="primary">{{ detailTypeMeta.label }}（{{ detailExpr?.type }}）</el-tag>
            <span class="rd-type-desc">{{ detailTypeMeta.desc }}</span>
          </div>
          <div v-if="detailExpr?.axiom" class="rd-axiom">
            引用公理 <code>{{ detailExpr.axiom }}</code>
          </div>
          <div class="wit-wrap">
            <div v-for="sec in detailWit" :key="sec.key" class="wit">
              <div class="wit-head">
                <span class="wit-badge" :class="sec.key">{{ sec.letter }}</span>
                <span class="wit-title">{{ sec.title }}</span>
                <span class="wit-note">{{ sec.note }}</span>
              </div>
              <div class="wit-rows">
                <div v-for="(item, i) in sec.items" :key="i" class="wit-row" :class="{ then: sec.key === 't' }">
                  <code>{{ item.code }}</code>
                  <span class="wit-arrow">→</span>
                  <span class="wit-zh">{{ item.zh }}</span>
                </div>
              </div>
            </div>
          </div>
        </template>
        <pre v-else class="dsl-block" v-html="highlightedExpr"></pre>

        <div class="rd-section-title">关联本体</div>
        <div class="rd-badges">
          <el-tag size="small" type="primary">{{ detailRule.conceptCode }}</el-tag>
          <el-tag v-if="detailRule.metricCode" size="small" type="info">{{ detailRule.metricCode }}</el-tag>
          <el-tag v-if="detailExpr?.axiom" size="small" type="warning">{{ detailExpr.axiom }}</el-tag>
          <el-tag size="small" effect="plain">{{ detailRule.ruleType }}</el-tag>
        </div>

        <div class="rd-section-title">命中与执行</div>
        <div class="rd-exec">
          <template v-if="detailRule.engine === 'QC'">
            <span>由 QC 质控引擎驱动，规则修改即生效、无需发版；命中明细沉淀在「数据治理」问题清单。</span>
            <el-button link type="primary" size="small" @click="goGov">前往数据治理 →</el-button>
          </template>
          <template v-else-if="detailRule.metricCode">
            <span>作为监控指标「{{ detailRule.metricCode }}」运行，实测值与阈值见「统一口径」指标库。</span>
            <el-button link type="primary" size="small" @click="goGlossary">前往统一口径 →</el-button>
          </template>
          <template v-else>
            <span>规则发布后由引擎执行，执行记录将在此沉淀。</span>
          </template>
        </div>

        <div class="rd-section-title">版本信息</div>
        <div class="rd-info">
          <div class="rd-info-row"><span class="rd-info-label">规则编码</span><span>{{ detailRule.ruleCode }}</span></div>
          <div class="rd-info-row"><span class="rd-info-label">负责人</span><span>{{ detailRule.owner || '-' }}</span></div>
          <div class="rd-info-row"><span class="rd-info-label">当前版本</span><span>v{{ detailRule.version }}</span></div>
          <div class="rd-info-row">
            <span class="rd-info-label">驱动方式</span>
            <span>{{ detailRule.engine === 'QC' ? 'QC 质控引擎（表达式）' : detailRule.metricCode ? '监控指标' : '无' }}</span>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 新建/编辑规则 -->
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建规则' : '编辑规则'" width="680px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="规则编码" required>
          <el-input v-model="form.ruleCode" :disabled="dialogMode === 'edit'" placeholder="如 RULE-STOCK-001" />
        </el-form-item>
        <el-form-item label="规则名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="关联概念" required>
          <el-select v-model="form.conceptCode" filterable style="width: 100%">
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="规则类型">
          <el-select v-model="form.ruleType" style="width: 100%">
            <el-option label="约束" value="约束" />
            <el-option label="推导" value="推导" />
            <el-option label="校验" value="校验" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重度">
          <el-select v-model="form.severity" style="width: 100%">
            <el-option label="高" value="高" />
            <el-option label="中" value="中" />
            <el-option label="低" value="低" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联指标">
          <el-select v-model="form.metricCode" clearable filterable style="width: 100%" placeholder="可不关联">
            <el-option
              v-for="m in metrics"
              :key="m.metricCode"
              :label="`${m.name}（${m.metricCode}）`"
              :value="m.metricCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="执行引擎">
          <el-select v-model="form.engine" style="width: 100%">
            <el-option label="无" value="" />
            <el-option v-if="!industrial" label="QC 质控引擎" value="QC" />
            <el-option v-if="industrial" label="工业约束契约（无执行器）" value="CONTRACT" />
          </el-select>
        </el-form-item>

        <!-- 表达式配置：engine = QC 时启用，双模态（可视化 / JSON 代码）同源等价 -->
        <template v-if="form.engine === 'QC'">
          <el-form-item label="表达式配置">
            <el-segmented
              v-model="exprTab"
              size="small"
              :options="[
                { label: '可视化配置', value: 'visual' },
                { label: 'JSON 代码', value: 'code' }
              ]"
            />
          </el-form-item>

          <template v-if="exprTab === 'visual'">
            <el-form-item label="规则类型">
              <el-select v-model="exprForm.type" style="width: 100%" :disabled="exprRaw">
                <el-option
                  v-for="t in EXPR_TYPES"
                  :key="t.value"
                  :label="`${t.label}（${t.value}）`"
                  :value="t.value"
                />
              </el-select>
              <div v-if="!exprRaw" class="expr-tip">{{ currentTypeMeta.desc }}</div>
            </el-form-item>

            <!-- 反解析失败：回退原始 JSON 编辑 -->
            <el-form-item v-if="exprRaw" label="原始 JSON">
              <div class="expr-raw">
                <el-alert
                  type="warning"
                  :closable="false"
                  title="该表达式无法解析为可视化表单，请直接编辑原始 JSON"
                  style="margin-bottom: 8px"
                />
                <el-input v-model="form.exprJson" type="textarea" :rows="6" class="dsl-editor" />
                <el-button link type="primary" size="small" @click="retryParse">
                  尝试解析为表单
                </el-button>
              </div>
            </el-form-item>

            <template v-else>
              <el-form-item v-if="exprForm.type !== 'DIAG_REQUIRES_ITEM'" label="引用公理">
                <el-input v-model="exprForm.axiom" placeholder="如 AX-001" style="width: 200px" />
              </el-form-item>

              <!-- cases：诊断必须有配套项目 / 并发症须有对应项目 -->
              <el-form-item v-if="hasCases" label="匹配项">
                <div class="case-list">
                  <div v-for="(c, i) in exprForm.cases" :key="i" class="case-item">
                    <div class="case-row">
                      <span class="case-label">诊断关键词</span>
                      <TagInput v-model="c.diagKeywords" placeholder="如 糖尿病" />
                    </div>
                    <div class="case-row">
                      <span class="case-label">项目类型</span>
                      <el-select v-model="c.kind" style="width: 160px">
                        <el-option label="检验（LAB）" value="LAB" />
                        <el-option label="药品（DRUG）" value="DRUG" />
                      </el-select>
                    </div>
                    <div class="case-row">
                      <span class="case-label">项目编码</span>
                      <TagInput v-model="c.codes" placeholder="如 L007" />
                    </div>
                    <div class="case-row">
                      <span class="case-label">要求项目</span>
                      <el-input v-model="c.requireName" placeholder="如 空腹血糖检验" style="width: 240px" />
                      <el-button
                        link type="danger" size="small"
                        :disabled="exprForm.cases.length <= 1"
                        @click="exprForm.cases.splice(i, 1)"
                      >删除</el-button>
                    </div>
                  </div>
                  <el-button size="small" @click="exprForm.cases.push(emptyCase())">添加匹配项</el-button>
                </div>
              </el-form-item>

              <!-- requires：术前必备检查 -->
              <el-form-item v-if="exprForm.type === 'PREOP_REQUIRES'" label="必查项目">
                <div class="case-list">
                  <div v-for="(r, i) in exprForm.requires" :key="i" class="case-item">
                    <div class="case-row">
                      <span class="case-label">项目类型</span>
                      <el-select v-model="r.kind" style="width: 160px">
                        <el-option label="检验（LAB）" value="LAB" />
                        <el-option label="药品（DRUG）" value="DRUG" />
                      </el-select>
                    </div>
                    <div class="case-row">
                      <span class="case-label">项目编码</span>
                      <TagInput v-model="r.codes" placeholder="如 L001" />
                    </div>
                    <div class="case-row">
                      <span class="case-label">要求项目</span>
                      <el-input v-model="r.requireName" placeholder="如 血常规" style="width: 240px" />
                      <el-button
                        link type="danger" size="small"
                        :disabled="exprForm.requires.length <= 1"
                        @click="exprForm.requires.splice(i, 1)"
                      >删除</el-button>
                    </div>
                  </div>
                  <el-button size="small" @click="exprForm.requires.push(emptyRequire())">添加必查项</el-button>
                </div>
              </el-form-item>

              <!-- 检验异常须有处置 -->
              <template v-if="exprForm.type === 'ABNORMAL_REQUIRES_COVER'">
                <el-form-item label="覆盖诊断词">
                  <TagInput v-model="exprForm.coverDiagKeywords" placeholder="如 感染" />
                </el-form-item>
                <el-form-item label="处置类型">
                  <el-select v-model="exprForm.coverKind" style="width: 160px">
                    <el-option label="药品（DRUG）" value="DRUG" />
                    <el-option label="检验（LAB）" value="LAB" />
                  </el-select>
                </el-form-item>
                <el-form-item label="处置编码">
                  <TagInput v-model="exprForm.coverCodes" placeholder="如 D006" />
                </el-form-item>
              </template>

              <!-- 性别互斥诊断 -->
              <template v-if="exprForm.type === 'SEX_DISJOINT_DIAG'">
                <el-form-item label="性别">
                  <el-radio-group v-model="exprForm.sex">
                    <el-radio value="男">男</el-radio>
                    <el-radio value="女">女</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="互斥诊断词">
                  <TagInput v-model="exprForm.diagKeywords" placeholder="如 妊娠" />
                </el-form-item>
              </template>

              <!-- 检查异常须处置 -->
              <template v-if="exprForm.type === 'EXAM_ABNORMAL_REQUIRES_COVER'">
                <el-form-item label="覆盖诊断词">
                  <TagInput v-model="exprForm.coverDiagKeywords" placeholder="如 占位" />
                </el-form-item>
                <el-form-item label="手术算处置">
                  <el-switch v-model="exprForm.coverProcedure" />
                </el-form-item>
              </template>

              <el-form-item label=" ">
                <div class="expr-json-toggle">
                  <el-button link type="primary" size="small" @click="showJson = !showJson">
                    {{ showJson ? '收起 JSON' : '查看生成的 JSON' }}
                  </el-button>
                  <pre v-if="showJson" class="dsl-block" v-html="editingHighlightedExpr"></pre>
                </div>
              </el-form-item>
            </template>
          </template>

          <el-form-item v-else label="JSON 代码">
            <div class="expr-raw">
              <el-input v-model="form.exprJson" type="textarea" :rows="10" class="dsl-editor" />
              <div class="expr-tip">直接编辑表达式 JSON；切回「可视化配置」时将尝试解析为表单</div>
            </div>
          </el-form-item>
        </template>

        <el-form-item label="规则表达">
          <el-input
            v-model="form.expression"
            type="textarea"
            :rows="3"
            placeholder="用业务语言声明，如：已冻结预留不得挪给其他需求"
          />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="form.owner" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <template v-if="dialogMode === 'create'">
          <el-button :loading="saving" @click="submit()">保存草稿</el-button>
          <el-button type="primary" :loading="saving" @click="submit('REVIEW')">保存并提交评审</el-button>
        </template>
        <el-button v-else type="primary" :loading="saving" @click="submit()">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { listRules, createRule, updateRule, transitionRule, deleteRule } from '../../../api/ontology'
import { listMetrics } from '../../../api/glossary'
import { statusText, statusTagType } from '../../../utils/dict'
import { useConceptStore } from '../../../store/concept'
import TagInput from './TagInput.vue'

const industrial = import.meta.env.VITE_INDUSTRY === 'forgepilot'
const router = useRouter()
const conceptStore = useConceptStore()

const rules = ref([])
const loading = ref(false)
const filterConcept = ref('')
const filterType = ref('')
const metrics = ref([])
const actingCode = ref('')

const ruleTypeTag = (t) => ({ 约束: 'danger', 推导: 'primary', 校验: 'warning' }[t] || 'info')
const severityTag = (s) => ({ 高: 'danger', 中: 'warning', 低: 'info' }[s] || 'info')

const loadRules = async () => {
  loading.value = true
  try {
    rules.value = await listRules(filterConcept.value || undefined)
  } finally {
    loading.value = false
  }
}

// ---------- 分类页签（数量徽标） ----------
const TYPE_ORDER = ['约束', '推导', '校验']

const typeTabs = computed(() => {
  const counts = {}
  rules.value.forEach((r) => {
    counts[r.ruleType] = (counts[r.ruleType] || 0) + 1
  })
  const tabs = [{ label: '全部规则', value: '', count: rules.value.length }]
  TYPE_ORDER.forEach((t) => {
    if (counts[t]) tabs.push({ label: `${t}规则`, value: t, count: counts[t] })
  })
  Object.keys(counts)
    .filter((t) => !TYPE_ORDER.includes(t))
    .forEach((t) => tabs.push({ label: `${t}规则`, value: t, count: counts[t] }))
  return tabs
})

const filteredRules = computed(() =>
  filterType.value ? rules.value.filter((r) => r.ruleType === filterType.value) : rules.value
)

const doTransition = async (row, target) => {
  actingCode.value = row.ruleCode
  try {
    await transitionRule(row.ruleCode, target)
    ElMessage.success('状态流转成功')
    loadRules()
  } finally {
    actingCode.value = ''
  }
}

// ---------- 表达式类型定义（与后端 QcRuleEngine 解释器一致） ----------
const EXPR_TYPES = [
  { value: 'DIAG_REQUIRES_ITEM', label: '诊断必须有配套项目', desc: '诊断关键词命中 → 要求存在已执行的检验/药品医嘱' },
  { value: 'PREOP_REQUIRES', label: '术前必备检查', desc: '存在手术 → 必查项目须已执行且早于手术时间' },
  { value: 'ABNORMAL_REQUIRES_COVER', label: '检验异常须有处置', desc: '检验危急值 → 须被覆盖诊断或处置医嘱覆盖' },
  { value: 'COMPLICATION_REQUIRES_ITEM', label: '并发症须有对应项目', desc: '并发症关键词命中 → 要求存在已执行的处置医嘱' },
  { value: 'SEX_DISJOINT_DIAG', label: '性别互斥诊断', desc: '指定性别患者出现互斥诊断关键词 → 违反公理' },
  { value: 'EXAM_ABNORMAL_REQUIRES_COVER', label: '检查异常须处置', desc: '检查报告异常 → 须被对应诊断或手术处置覆盖' }
]

const KIND_LABEL = { LAB: '检验', DRUG: '药品' }

const emptyCase = () => ({ diagKeywords: [], kind: 'LAB', codes: [], requireName: '' })
const emptyRequire = () => ({ kind: 'LAB', codes: [], requireName: '' })

const defaultExprForm = () => ({
  type: 'DIAG_REQUIRES_ITEM',
  axiom: '',
  cases: [emptyCase()],
  requires: [emptyRequire()],
  coverDiagKeywords: [],
  coverKind: 'DRUG',
  coverCodes: [],
  sex: '男',
  diagKeywords: [],
  coverProcedure: true
})

// ---------- JSON 语法高亮（深色代码块） ----------
const escapeHtml = (s) =>
  String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

const highlightJson = (obj) =>
  escapeHtml(JSON.stringify(obj, null, 2)).replace(
    /("(?:\\.|[^"\\])*")(\s*:)?|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?/g,
    (m, str, colon) => {
      if (str) return `<span class="${colon ? 'kw' : 'str'}">${str}</span>${colon || ''}`
      return `<span class="num">${m}</span>`
    }
  )

// ---------- 规则详情抽屉（双模态：可视化 WHEN/WHERE/THEN ⇄ 规则代码） ----------
const detailVisible = ref(false)
const detailRule = ref(null)
const detailMode = ref('visual')

const hasExpr = computed(() => !!detailRule.value?.exprJson)

const detailExpr = computed(() => {
  const raw = detailRule.value?.exprJson
  if (!raw) return null
  try {
    const e = JSON.parse(raw)
    return e && typeof e === 'object' ? e : null
  } catch {
    return null
  }
})

const detailTypeMeta = computed(() => {
  const e = detailExpr.value
  if (!e) return null
  return EXPR_TYPES.find((t) => t.value === e.type) || null
})

const highlightedExpr = computed(() => {
  const raw = detailRule.value?.exprJson
  if (!raw) return ''
  try {
    return highlightJson(JSON.parse(raw))
  } catch {
    return escapeHtml(raw)
  }
})

// 把 6 种 QC 规则类型映射为 WHEN/WHERE/THEN 三段式（每段：代码片段 + 中文解释）
const kw = (list) => (Array.isArray(list) && list.length ? list.join('、') : '（未配置）')
const kindOf = (k) => KIND_LABEL[k] || k || '项目'

const buildWit = (e, r) => {
  switch (e.type) {
    case 'DIAG_REQUIRES_ITEM':
    case 'COMPLICATION_REQUIRES_ITEM': {
      const cases = Array.isArray(e.cases) && e.cases.length ? e.cases : []
      const triggerZh = e.type === 'DIAG_REQUIRES_ITEM' ? '诊断命中关键词，触发规则评估' : '并发症关键词命中，触发规则评估'
      return [
        {
          key: 'w', letter: 'W', title: '触发条件 (WHEN)', note: '匹配业务数据模式',
          items: cases.map((c) => ({ code: `diagnosis ∋ [${kw(c.diagKeywords)}]`, zh: triggerZh }))
        },
        {
          key: 'i', letter: 'I', title: '判定条件 (WHERE)', note: '检查配套医嘱是否已执行',
          items: cases.map((c) => ({
            code: `¬∃ ${kindOf(c.kind)}Order(codes ∈ [${kw(c.codes)}])`,
            zh: `缺少已执行的${kindOf(c.kind)}医嘱`
          }))
        },
        {
          key: 't', letter: 'T', title: '执行动作 (THEN)', note: '生成质控缺陷',
          items: cases.map((c) => ({ code: `require "${c.requireName || '（未命名项目）'}"`, zh: '要求存在对应已执行项目，否则生成缺陷' }))
        }
      ]
    }
    case 'PREOP_REQUIRES': {
      const reqs = Array.isArray(e.requires) && e.requires.length ? e.requires : []
      return [
        {
          key: 'w', letter: 'W', title: '触发条件 (WHEN)', note: '存在手术安排',
          items: [{ code: '∃ SurgeryOrder', zh: '患者存在手术医嘱/手术安排' }]
        },
        {
          key: 'i', letter: 'I', title: '判定条件 (WHERE)', note: '必查项目执行状态与时序',
          items: reqs.map((req) => ({
            code: `¬∃ ${kindOf(req.kind)}(codes ∈ [${kw(req.codes)}], time < surgery.time)`,
            zh: `必查项目未执行或执行晚于手术时间`
          }))
        },
        {
          key: 't', letter: 'T', title: '执行动作 (THEN)', note: '生成质控缺陷',
          items: reqs.map((req) => ({ code: `require "${req.requireName || '（未命名项目）'}"`, zh: '要求术前完成该项目，否则生成缺陷' }))
        }
      ]
    }
    case 'ABNORMAL_REQUIRES_COVER':
      return [
        {
          key: 'w', letter: 'W', title: '触发条件 (WHEN)', note: '检验结果异常',
          items: [{ code: 'labResult.abnormal == true', zh: '检验出现危急值/异常结果' }]
        },
        {
          key: 'i', letter: 'I', title: '判定条件 (WHERE)', note: '异常是否已被覆盖',
          items: [{
            code: `¬coveredBy(diag ∋ [${kw(e.coverDiagKeywords)}] | ${kindOf(e.coverKind)} ∈ [${kw(e.coverCodes)}])`,
            zh: '未被覆盖诊断或处置医嘱覆盖'
          }]
        },
        {
          key: 't', letter: 'T', title: '执行动作 (THEN)', note: '生成缺陷',
          items: [{ code: 'raise QualityDefect', zh: '生成「检验异常未处置」缺陷记录' }]
        }
      ]
    case 'SEX_DISJOINT_DIAG':
      return [
        {
          key: 'w', letter: 'W', title: '触发条件 (WHEN)', note: '患者性别匹配',
          items: [{ code: `patient.sex == "${e.sex || '?'}"`, zh: `患者性别为「${e.sex || '?'}」` }]
        },
        {
          key: 'i', letter: 'I', title: '判定条件 (WHERE)', note: '诊断含互斥关键词',
          items: [{ code: `diagnosis ∋ [${kw(e.diagKeywords)}]`, zh: '诊断内容命中互斥关键词' }]
        },
        {
          key: 't', letter: 'T', title: '执行动作 (THEN)', note: '判定违反公理',
          items: [{ code: `violate ${e.axiom || 'axiom'}`, zh: '判定违反公理，生成缺陷记录' }]
        }
      ]
    case 'EXAM_ABNORMAL_REQUIRES_COVER':
      return [
        {
          key: 'w', letter: 'W', title: '触发条件 (WHEN)', note: '检查报告异常',
          items: [{ code: 'examReport.abnormal == true', zh: '检查报告结论提示异常' }]
        },
        {
          key: 'i', letter: 'I', title: '判定条件 (WHERE)', note: '异常是否已被处置',
          items: [{
            code: `¬coveredBy(diag ∋ [${kw(e.coverDiagKeywords)}]${e.coverProcedure ? ' | procedure' : ''})`,
            zh: e.coverProcedure ? '未被对应诊断或手术处置覆盖（手术记录视为处置）' : '未被对应诊断覆盖'
          }]
        },
        {
          key: 't', letter: 'T', title: '执行动作 (THEN)', note: '生成提醒',
          items: [{ code: 'raise QualityDefect', zh: '生成「检查异常未处置」提醒' }]
        }
      ]
    default:
      return null
  }
}

const detailWit = computed(() => {
  const r = detailRule.value
  if (!r) return []
  const e = detailExpr.value
  if (e) {
    const wit = buildWit(e, r)
    if (wit) return wit
  }
  // 非表达式驱动 / 表达式未识别：以业务语言表达
  return [
    {
      key: 'w', letter: 'W', title: '触发条件 (WHEN)', note: '关联概念数据进入评估',
      items: [{ code: r.conceptCode, zh: `关联概念「${r.conceptCode}」的数据进入规则评估` }]
    },
    {
      key: 'i', letter: 'I', title: '判定条件 (WHERE)', note: '业务语言声明',
      items: [{ code: r.expression || '（未填写业务语言描述）', zh: '按业务语言描述执行判定' }]
    },
    {
      key: 't', letter: 'T', title: '执行动作 (THEN)', note: '输出结果',
      items: [{
        code: r.metricCode ? `metric: ${r.metricCode}` : 'raise alert',
        zh: r.metricCode ? '产出监控指标观测值' : '产生规则告警/记录'
      }]
    }
  ]
})

const openDetail = (row, mode = 'visual') => {
  detailRule.value = row
  detailMode.value = mode === 'code' && row.exprJson ? 'code' : 'visual'
  detailVisible.value = true
}

const goGov = () => {
  detailVisible.value = false
  router.push('/gov')
}

const goGlossary = () => {
  detailVisible.value = false
  router.push('/glossary')
}

// ---------- 新建/编辑 ----------
const dialogVisible = ref(false)
const dialogMode = ref('create')
const saving = ref(false)
const form = reactive({
  id: null,
  ruleCode: '',
  name: '',
  conceptCode: '',
  ruleType: '约束',
  severity: '中',
  metricCode: '',
  engine: '',
  exprJson: '',
  expression: '',
  owner: ''
})

// ---------- 表达式构建器 ----------
const exprForm = reactive(defaultExprForm())
const exprRaw = ref(false)
const exprTab = ref('visual')
const showJson = ref(false)

const currentTypeMeta = computed(
  () => EXPR_TYPES.find((t) => t.value === exprForm.type) || EXPR_TYPES[0]
)
const hasCases = computed(() =>
  ['DIAG_REQUIRES_ITEM', 'COMPLICATION_REQUIRES_ITEM'].includes(exprForm.type)
)

const editingHighlightedExpr = computed(() => {
  if (!form.exprJson) return ''
  try {
    return highlightJson(JSON.parse(form.exprJson))
  } catch {
    return escapeHtml(form.exprJson)
  }
})

const buildExpr = () => {
  const f = exprForm
  const cleanCases = (list) =>
    list.map((c) => ({
      diagKeywords: c.diagKeywords,
      kind: c.kind,
      codes: c.codes,
      requireName: c.requireName
    }))
  switch (f.type) {
    case 'PREOP_REQUIRES':
      return {
        type: f.type,
        axiom: f.axiom,
        requires: f.requires.map((r) => ({ kind: r.kind, codes: r.codes, requireName: r.requireName }))
      }
    case 'ABNORMAL_REQUIRES_COVER':
      return {
        type: f.type,
        axiom: f.axiom,
        coverDiagKeywords: f.coverDiagKeywords,
        coverKind: f.coverKind,
        coverCodes: f.coverCodes
      }
    case 'COMPLICATION_REQUIRES_ITEM':
      return { type: f.type, axiom: f.axiom, cases: cleanCases(f.cases) }
    case 'SEX_DISJOINT_DIAG':
      return { type: f.type, axiom: f.axiom, sex: f.sex, diagKeywords: f.diagKeywords }
    case 'EXAM_ABNORMAL_REQUIRES_COVER':
      return {
        type: f.type,
        axiom: f.axiom,
        coverDiagKeywords: f.coverDiagKeywords,
        coverProcedure: f.coverProcedure
      }
    default:
      return { type: 'DIAG_REQUIRES_ITEM', cases: cleanCases(f.cases) }
  }
}

// 表单值实时生成 expr_json
watch(
  exprForm,
  () => {
    if (form.engine === 'QC' && !exprRaw.value) {
      form.exprJson = JSON.stringify(buildExpr())
    }
  },
  { deep: true }
)

watch(
  () => form.engine,
  (v) => {
    if (v === 'QC' && !exprRaw.value) {
      form.exprJson = JSON.stringify(buildExpr())
    }
  }
)

// 切回可视化时尝试把 JSON 反解析为表单；失败则留在代码模式
watch(exprTab, (v) => {
  if (v === 'visual' && form.engine === 'QC' && form.exprJson) {
    if (parseExpr(form.exprJson)) {
      exprRaw.value = false
    } else {
      exprTab.value = 'code'
      ElMessage.warning('JSON 无法解析为可视化表单，请检查格式或继续编辑代码')
    }
  }
})

const fillExprForm = (e) => {
  const f = defaultExprForm()
  f.type = e.type
  if (typeof e.axiom === 'string') f.axiom = e.axiom
  if (Array.isArray(e.cases) && e.cases.length) {
    f.cases = e.cases.map((c) => ({
      diagKeywords: asStrList(c?.diagKeywords),
      kind: c?.kind === 'DRUG' ? 'DRUG' : 'LAB',
      codes: asStrList(c?.codes),
      requireName: typeof c?.requireName === 'string' ? c.requireName : ''
    }))
  }
  if (Array.isArray(e.requires) && e.requires.length) {
    f.requires = e.requires.map((r) => ({
      kind: r?.kind === 'DRUG' ? 'DRUG' : 'LAB',
      codes: asStrList(r?.codes),
      requireName: typeof r?.requireName === 'string' ? r.requireName : ''
    }))
  }
  f.coverDiagKeywords = asStrList(e.coverDiagKeywords)
  if (typeof e.coverKind === 'string' && e.coverKind) f.coverKind = e.coverKind
  f.coverCodes = asStrList(e.coverCodes)
  if (e.sex === '女') f.sex = '女'
  f.diagKeywords = asStrList(e.diagKeywords)
  if (typeof e.coverProcedure === 'boolean') f.coverProcedure = e.coverProcedure
  Object.assign(exprForm, f)
}

const asStrList = (v) => (Array.isArray(v) ? v.map(String) : [])

// 反解析 expr_json 回表单；失败返回 false，由调用方回退原始 JSON
const parseExpr = (jsonStr) => {
  try {
    const e = JSON.parse(jsonStr)
    if (!e || !EXPR_TYPES.some((t) => t.value === e.type)) return false
    fillExprForm(e)
    return true
  } catch {
    return false
  }
}

const retryParse = () => {
  if (parseExpr(form.exprJson)) {
    exprRaw.value = false
  } else {
    ElMessage.warning('仍无法解析，请检查 JSON 格式与规则类型')
  }
}

const openDialog = (mode, row) => {
  dialogMode.value = mode
  exprRaw.value = false
  exprTab.value = 'visual'
  showJson.value = false
  if (mode === 'create') {
    Object.assign(form, {
      id: null,
      ruleCode: '',
      name: '',
      conceptCode: filterConcept.value || '',
      ruleType: '约束',
      severity: '中',
      metricCode: '',
      engine: '',
      exprJson: '',
      expression: '',
      owner: ''
    })
    Object.assign(exprForm, defaultExprForm())
  } else {
    Object.assign(form, {
      id: row.id,
      ruleCode: row.ruleCode,
      name: row.name,
      conceptCode: row.conceptCode,
      ruleType: row.ruleType,
      severity: row.severity,
      metricCode: row.metricCode || '',
      engine: row.engine || '',
      exprJson: row.exprJson || '',
      expression: row.expression,
      owner: row.owner
    })
    if (form.engine === 'QC' && form.exprJson) {
      exprRaw.value = !parseExpr(form.exprJson)
      exprTab.value = exprRaw.value ? 'code' : 'visual'
    } else {
      Object.assign(exprForm, defaultExprForm())
    }
  }
  dialogVisible.value = true
}

const submit = async (after) => {
  if (!form.ruleCode || !form.name || !form.conceptCode) {
    ElMessage.warning('请填写规则编码、名称和关联概念')
    return
  }
  if (form.engine === 'QC') {
    if (!form.exprJson) {
      ElMessage.warning('请配置规则表达式')
      return
    }
    try {
      JSON.parse(form.exprJson)
    } catch {
      ElMessage.warning('规则表达式 JSON 格式有误')
      return
    }
  }
  saving.value = true
  try {
    const payload = {
      ruleCode: form.ruleCode,
      name: form.name,
      conceptCode: form.conceptCode,
      ruleType: form.ruleType,
      severity: form.severity,
      metricCode: form.metricCode || null,
      engine: form.engine || null,
      exprJson: form.engine === 'QC' ? form.exprJson || null : null,
      expression: form.expression,
      owner: form.owner
    }
    if (dialogMode.value === 'create') {
      await createRule(payload)
      if (after === 'REVIEW') {
        await transitionRule(form.ruleCode, 'REVIEW')
        ElMessage.success('已创建并提交评审')
      } else {
        ElMessage.success('规则创建成功（草稿）')
      }
    } else {
      const row = rules.value.find((r) => r.id === form.id)
      await updateRule({ ...row, ...payload })
      ElMessage.success('规则已更新')
    }
    dialogVisible.value = false
    loadRules()
  } finally {
    saving.value = false
  }
}

const removeRule = async (row) => {
  await ElMessageBox.confirm(`确认删除规则「${row.name}」？`, '提示', { type: 'warning' })
  await deleteRule(row.id)
  ElMessage.success('已删除')
  loadRules()
}

onMounted(() => {
  conceptStore.fetchAll()
  listMetrics().then((res) => (metrics.value = res))
  loadRules()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.filter-bar-action {
  margin-left: auto;
}

/* ---------- 分类页签（下划线式 + 数量徽标，规范 §3） ---------- */
.rule-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 2px solid var(--border-color);
  margin-bottom: 12px;
}

.rule-tab {
  padding: 8px 16px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  position: relative;
  font-weight: 500;
  transition: var(--transition);
}

.rule-tab:hover {
  color: var(--primary);
}

.rule-tab.active {
  color: var(--primary);
}

.rule-tab.active::after {
  content: '';
  position: absolute;
  bottom: -2px;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--primary);
  border-radius: 2px;
}

.tab-count {
  font-size: 11px;
  background: var(--el-fill-color-light);
  color: var(--text-secondary);
  border-radius: 8px;
  padding: 1px 6px;
  margin-left: 4px;
}

/* ---------- 深色代码块（规范 §3 code-block） ---------- */
.dsl-block {
  margin: 0;
  padding: 14px 16px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 8px;
  font-family: 'SF Mono', 'SFMono-Regular', Monaco, Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}

.dsl-block :deep(.kw) {
  color: #c084fc;
}

.dsl-block :deep(.str) {
  color: #34d399;
}

.dsl-block :deep(.num) {
  color: #fbbf24;
}

.dsl-editor :deep(textarea) {
  background: #0f172a;
  color: #e2e8f0;
  border-color: #1e293b;
  font-family: 'SF Mono', 'SFMono-Regular', Monaco, Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
}

.dsl-editor :deep(textarea):focus {
  border-color: var(--primary);
}

/* ---------- 规则详情抽屉 ---------- */
.rd-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-color);
}

.rd-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.rd-meta {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
}

.rd-header-side {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.rd-close {
  cursor: pointer;
  color: var(--text-muted);
  font-size: 16px;
  margin-left: 4px;
}

.rd-close:hover {
  color: var(--text-primary);
}

.rd-section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 18px 0 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-color);
}

.rd-section-title-flat {
  margin: 0;
  padding-bottom: 0;
  border-bottom: none;
}

.rd-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 18px 0 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-color);
}

.rd-desc {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
  margin: 0;
}

.rd-type-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.rd-type-desc {
  font-size: 12px;
  color: var(--text-muted);
}

.rd-axiom {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 10px;
}

.rd-axiom code {
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  background: var(--el-fill-color-light);
  color: var(--primary-dark);
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
}

/* WHEN/WHERE/THEN 三段式（规范 §4.4：段首字母徽章 + 代码片段配中文解释） */
.wit-wrap {
  background: #fafbfc;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 14px;
}

.wit {
  margin-bottom: 14px;
}

.wit:last-child {
  margin-bottom: 0;
}

.wit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.wit-badge {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.wit-badge.w {
  background: var(--accent-blue);
}

.wit-badge.i {
  background: var(--warning);
}

.wit-badge.t {
  background: var(--success);
}

.wit-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.wit-note {
  font-size: 11px;
  color: var(--text-muted);
}

.wit-rows {
  padding-left: 30px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wit-row {
  background: #fff;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.wit-row code {
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  color: var(--primary-dark);
  background: var(--el-fill-color-light);
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  word-break: break-all;
}

.wit-arrow {
  color: var(--text-muted);
}

.wit-zh {
  color: var(--text-secondary);
  font-size: 11px;
}

.wit-row.then {
  background: linear-gradient(135deg, var(--success-light), #d1fae5);
  border-color: #6ee7b7;
}

.rd-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.rd-exec {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.rd-info-row {
  display: flex;
  font-size: 13px;
  padding: 4px 0;
}

.rd-info-label {
  color: var(--text-muted);
  width: 90px;
  flex-shrink: 0;
}

/* ---------- 表达式构建器 ---------- */
.expr-tip {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
}

.expr-raw {
  width: 100%;
}

.expr-json-toggle {
  width: 100%;
}

.case-list {
  width: 100%;
}

.case-item {
  border: 1px solid var(--border-color);
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 10px;
}

.case-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.case-row:last-child {
  margin-bottom: 0;
}

.case-label {
  width: 70px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
