import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {
  AlertCircle,
  Check,
  CheckCircle,
  ChevronDown,
  Clock,
  Database,
  Download,
  Edit3,
  Eye,
  FileText,
  HardDrive,
  Loader2,
  MessageSquare,
  RefreshCw,
  Search,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import {knowledgeBaseApi, KnowledgeBaseItem, KnowledgeBaseStats, SortOption, VectorStatus,} from '../api/knowledgebase';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import ContextSelector from '../components/ContextSelector';
import { applyFilters, type FilterState } from '../utils/kbFilter';

interface KnowledgeBaseManagePageProps {
  onUpload: () => void;
  onChat: () => void;
  /** 是否在列表上方显示上下文范围选择器（DocsCenterPage 使用） */
  showContextSelector?: boolean;
}

// 格式化文件大小
function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// 格式化日期
function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// 状态图标组件
function StatusIcon({ status }: { status: VectorStatus }) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle className="w-4 h-4 text-green-500" />;
    case 'PROCESSING':
      return <Loader2 className="w-4 h-4 text-blue-500 animate-spin" />;
    case 'PENDING':
      return <Clock className="w-4 h-4 text-yellow-500" />;
    case 'FAILED':
      return <AlertCircle className="w-4 h-4 text-red-500" />;
    default:
      return <CheckCircle className="w-4 h-4 text-green-500" />;
  }
}

// 状态文本
function getStatusText(status: VectorStatus): string {
  switch (status) {
    case 'COMPLETED':
      return '已完成';
    case 'PROCESSING':
      return '处理中';
    case 'PENDING':
      return '待处理';
    case 'FAILED':
      return '失败';
    default:
      return '未知';
  }
}

// 统计卡片组件
function StatCard({
  icon: Icon,
  label,
  value,
  color,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number;
  color: string;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-slate-700"
    >
      <div className="flex items-center gap-4">
        <div className={`p-3 rounded-lg ${color}`}>
          <Icon className="w-6 h-6 text-white" />
        </div>
        <div>
            <p className="text-sm text-slate-500 dark:text-slate-400">{label}</p>
            <p className="text-2xl font-bold text-slate-800 dark:text-white">{value.toLocaleString()}</p>
        </div>
      </div>
    </motion.div>
  );
}

export default function KnowledgeBaseManagePage({ onUpload, onChat, showContextSelector }: KnowledgeBaseManagePageProps) {
  const [stats, setStats] = useState<KnowledgeBaseStats | null>(null);
  // 原始列表（从后端获取，未经搜索/分类/服务/环境筛选）
  const [allKnowledgeBases, setAllKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [sortBy, setSortBy] = useState<SortOption>('time');
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [categories, setCategories] = useState<string[]>([]);
  const [deleteItem, setDeleteItem] = useState<KnowledgeBaseItem | null>(null);
  const [deleting, setDeleting] = useState(false);

  // 分类编辑状态
  const [editingCategoryId, setEditingCategoryId] = useState<number | null>(null);
  const [editingCategoryValue, setEditingCategoryValue] = useState('');
  const [savingCategory, setSavingCategory] = useState(false);
  const categoryInputRef = useRef<HTMLInputElement>(null);

  // 服务/环境标签状态
  const [serviceFilter, setServiceFilter] = useState<FilterState>({ type: 'all' });
  const [environmentFilter, setEnvironmentFilter] = useState<FilterState>({ type: 'all' });
  const [services, setServices] = useState<string[]>([]);
  const [environments, setEnvironments] = useState<string[]>([]);

  // 上下文范围选择器状态
  const [contextService, setContextService] = useState<string>('');
  const [contextEnvironment, setContextEnvironment] = useState<string>('');

  // 服务/环境编辑状态
  const [editingLabelsId, setEditingLabelsId] = useState<number | null>(null);
  const [editingService, setEditingService] = useState('');
  const [editingEnvironment, setEditingEnvironment] = useState('');
  const [savingLabels, setSavingLabels] = useState(false);
  const serviceInputRef = useRef<HTMLInputElement>(null);

  // 重新向量化状态
  const [revectorizing, setRevectorizing] = useState<number | null>(null);

  // 上下文范围 + 客户端组合筛选：搜索 + 分类 + service + environment 同时生效
  const filteredKnowledgeBases = useMemo(() => {
    let items = allKnowledgeBases;

    // 上下文范围筛选（优先级最高，来自 ContextSelector）
    if (contextService) {
      items = items.filter(kb => kb.service === contextService);
    }
    if (contextEnvironment) {
      items = items.filter(kb => kb.environment === contextEnvironment);
    }

    // 搜索关键词筛选
    if (searchKeyword.trim()) {
      const kw = searchKeyword.trim().toLowerCase();
      items = items.filter(kb =>
        kb.name.toLowerCase().includes(kw) ||
        (kb.originalFilename && kb.originalFilename.toLowerCase().includes(kw))
      );
    }

    // 分类筛选
    if (selectedCategory) {
      items = items.filter(kb => kb.category === selectedCategory);
    }

    // service + environment 手动筛选（来自页面内下拉框）
    return applyFilters(items, serviceFilter, environmentFilter);
  }, [allKnowledgeBases, searchKeyword, selectedCategory, serviceFilter, environmentFilter, contextService, contextEnvironment]);

  // 排序（在筛选之后）
  const knowledgeBases = useMemo(() => {
    const items = [...filteredKnowledgeBases];
    switch (sortBy) {
      case 'size':
        return items.sort((a, b) => b.fileSize - a.fileSize);
      case 'access':
        return items.sort((a, b) => b.accessCount - a.accessCount);
      case 'question':
        return items.sort((a, b) => b.questionCount - a.questionCount);
      default:
        // time: 按 uploadedAt 降序
        return items.sort((a, b) => new Date(b.uploadedAt).getTime() - new Date(a.uploadedAt).getTime());
    }
  }, [filteredKnowledgeBases, sortBy]);

  // 加载数据（不显示loading状态，用于轮询）
  const loadDataSilent = useCallback(async () => {
    try {
      const [statsData, kbList, categoryList, servicesList, envsList] = await Promise.all([
        knowledgeBaseApi.getStatistics(),
        knowledgeBaseApi.getAllKnowledgeBases(),
        knowledgeBaseApi.getAllCategories(),
        knowledgeBaseApi.getAllServices(),
        knowledgeBaseApi.getAllEnvironments(),
      ]);
      setStats(statsData);
      setAllKnowledgeBases(kbList);
      setCategories(categoryList);
      setServices(servicesList);
      setEnvironments(envsList);
    } catch (error) {
      console.error('加载数据失败:', error);
    }
  }, []);

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [statsData, kbList, categoryList, servicesList, envsList] = await Promise.all([
        knowledgeBaseApi.getStatistics(),
        knowledgeBaseApi.getAllKnowledgeBases(),
        knowledgeBaseApi.getAllCategories(),
        knowledgeBaseApi.getAllServices(),
        knowledgeBaseApi.getAllEnvironments(),
      ]);
      setStats(statsData);
      setAllKnowledgeBases(kbList);
      setCategories(categoryList);
      setServices(servicesList);
      setEnvironments(envsList);
    } catch (error) {
      console.error('加载数据失败:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // 筛选条件变化时不再需要重新请求后端，useMemo 会自动重新计算

  // 轮询：当有 PENDING 或 PROCESSING 状态时，每5秒刷新一次
  useEffect(() => {
    const hasPendingItems = knowledgeBases.some(
      kb => kb.vectorStatus === 'PENDING' || kb.vectorStatus === 'PROCESSING'
    );

    if (hasPendingItems && !loading) {
      const timer = setInterval(() => {
        loadDataSilent();
      }, 5000);

      return () => clearInterval(timer);
    }
  }, [knowledgeBases, loading, loadDataSilent]);

  // 重新向量化
  const handleRevectorize = async (id: number) => {
    try {
      setRevectorizing(id);
      await knowledgeBaseApi.revectorize(id);
      await loadDataSilent();
    } catch (error) {
      console.error('重新向量化失败:', error);
    } finally {
      setRevectorizing(null);
    }
  };

  // 删除知识库
  const handleDelete = async () => {
    if (!deleteItem) return;
    try {
      setDeleting(true);
      await knowledgeBaseApi.deleteKnowledgeBase(deleteItem.id);
      setDeleteItem(null);
      await loadData();
    } catch (error) {
      console.error('删除失败:', error);
    } finally {
      setDeleting(false);
    }
  };

  // 下载知识库
    const handleDownload = async (kb: KnowledgeBaseItem) => {
        try {
            const blob = await knowledgeBaseApi.downloadKnowledgeBase(kb.id);
            const url = window.URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = kb.originalFilename;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            window.URL.revokeObjectURL(url);
        } catch (error) {
            console.error('下载失败:', error);
        }
  };

  // 开始编辑分类
  const handleStartEditCategory = (kb: KnowledgeBaseItem) => {
    setEditingCategoryId(kb.id);
    setEditingCategoryValue(kb.category || '');
    setTimeout(() => {
      categoryInputRef.current?.focus();
    }, 50);
  };

  // 取消编辑分类
  const handleCancelEditCategory = () => {
    setEditingCategoryId(null);
    setEditingCategoryValue('');
  };

  // 保存分类
  const handleSaveCategory = async (id: number) => {
    try {
      setSavingCategory(true);
      const categoryToSave = editingCategoryValue.trim() || null;
      await knowledgeBaseApi.updateCategory(id, categoryToSave);
      setEditingCategoryId(null);
      setEditingCategoryValue('');
      await loadData();
    } catch (error) {
      console.error('更新分类失败:', error);
    } finally {
      setSavingCategory(false);
    }
  };

  // 处理分类输入框按键
  const handleCategoryKeyDown = (e: React.KeyboardEvent, id: number) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSaveCategory(id);
    } else if (e.key === 'Escape') {
      handleCancelEditCategory();
    }
  };

  // 开始编辑服务/环境标签
  const handleStartEditLabels = (kb: KnowledgeBaseItem) => {
    setEditingLabelsId(kb.id);
    setEditingService(kb.service || '');
    setEditingEnvironment(kb.environment || '');
    setTimeout(() => {
      serviceInputRef.current?.focus();
    }, 50);
  };

  // 取消编辑标签
  const handleCancelEditLabels = () => {
    setEditingLabelsId(null);
    setEditingService('');
    setEditingEnvironment('');
  };

  // 保存标签
  const handleSaveLabels = async (id: number) => {
    try {
      const trimmedService = editingService.trim();
      const trimmedEnv = editingEnvironment.trim();
      if (trimmedService.length > 100) {
        alert('服务名称不能超过100个字符');
        return;
      }
      if (trimmedEnv.length > 50) {
        alert('环境名称不能超过50个字符');
        return;
      }
      setSavingLabels(true);
      const serviceToSave = trimmedService || undefined;
      const envToSave = trimmedEnv || undefined;
      await knowledgeBaseApi.updateLabels(id, serviceToSave, envToSave);
      setEditingLabelsId(null);
      setEditingService('');
      setEditingEnvironment('');
      await loadData();
    } catch (error) {
      console.error('更新标签失败:', error);
    } finally {
      setSavingLabels(false);
    }
  };

  // 处理标签输入框按键
  const handleLabelsKeyDown = (e: React.KeyboardEvent, id: number) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSaveLabels(id);
    } else if (e.key === 'Escape') {
      handleCancelEditLabels();
    }
  };

  // 搜索处理（现在通过 useMemo 实时过滤，保留方法以备扩展）
  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    // 搜索已通过 useMemo 自动生效，无需额外操作
  };

  return (
    <div className="max-w-7xl mx-auto">
      {/* 页面标题 */}
      <div className="flex items-center justify-between mb-8">
        <div>
            <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
            <Database className="w-7 h-7 text-primary-500" />
            知识库管理
          </h1>
            <p className="text-slate-500 dark:text-slate-400 mt-1">管理您的知识库文件，查看使用统计</p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={onUpload}
            className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 transition-colors"
          >
            <Upload className="w-4 h-4" />
            上传知识库
          </button>
          <button
            onClick={onChat}
            className="flex items-center gap-2 px-4 py-2 bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
          >
            <MessageSquare className="w-4 h-4" />
            问答助手
          </button>
        </div>
      </div>
      {/* 统计卡片 */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <StatCard
            icon={Database}
            label="知识库总数"
            value={stats.totalCount}
            color="bg-primary-500"
          />
          <StatCard
            icon={MessageSquare}
            label="总提问次数"
            value={stats.totalQuestionCount}
            color="bg-indigo-500"
          />
          <StatCard
            icon={Eye}
            label="总访问次数"
            value={stats.totalAccessCount}
            color="bg-emerald-500"
          />
        </div>
      )}

      {/* 上下文范围选择器 */}
      {showContextSelector && (
        <ContextSelector
          onScopeChange={(kbIds, service, environment) => {
            setContextService(service || '');
            setContextEnvironment(environment || '');
          }}
        />
      )}

      {/* 搜索和筛选栏 */}
        <div
            className="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-100 dark:border-slate-700 mb-6">
        <div className="flex flex-wrap items-center gap-4">
          {/* 搜索框 */}
          <form onSubmit={handleSearch} className="flex-1 min-w-[200px]">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchKeyword}
                onChange={(e) => setSearchKeyword(e.target.value)}
                placeholder="搜索知识库名称..."
                className="w-full pl-10 pr-4 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
              />
            </div>
          </form>

          {/* 排序选择 */}
          <div className="relative">
            <select
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as SortOption)}
              className="appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer"
            >
              <option value="time">按时间排序</option>
              <option value="size">按大小排序</option>
              <option value="access">按访问排序</option>
              <option value="question">按提问排序</option>
            </select>
            <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
          </div>

          {/* 分类筛选 */}
          <div className="relative">
            <select
              value={selectedCategory || ''}
              onChange={(e) => setSelectedCategory(e.target.value || null)}
              className="appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer"
            >
              <option value="">全部分类</option>
              {categories.map((cat) => (
                <option key={cat} value={cat}>
                  {cat}
                </option>
              ))}
            </select>
            <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
          </div>

          {/* 服务筛选 */}
          <div className="relative">
            <select
              value={serviceFilter.type === 'all' ? 'all' : serviceFilter.type === 'unclassified' ? 'unclassified' : `val:${serviceFilter.value}`}
              onChange={(e) => {
                const val = e.target.value;
                if (val === 'all') setServiceFilter({ type: 'all' });
                else if (val === 'unclassified') setServiceFilter({ type: 'unclassified' });
                else setServiceFilter({ type: 'value', value: val.slice(4) });
              }}
              className="appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer"
            >
              <option value="all">全部服务</option>
              <option value="unclassified">未分类</option>
              {services.map((s) => (
                <option key={s} value={`val:${s}`}>{s}</option>
              ))}
            </select>
            <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
          </div>

          {/* 环境筛选 */}
          <div className="relative">
            <select
              value={environmentFilter.type === 'all' ? 'all' : environmentFilter.type === 'unclassified' ? 'unclassified' : `val:${environmentFilter.value}`}
              onChange={(e) => {
                const val = e.target.value;
                if (val === 'all') setEnvironmentFilter({ type: 'all' });
                else if (val === 'unclassified') setEnvironmentFilter({ type: 'unclassified' });
                else setEnvironmentFilter({ type: 'value', value: val.slice(4) });
              }}
              className="appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer"
            >
              <option value="all">全部环境</option>
              <option value="unclassified">未分类</option>
              {environments.map((env) => (
                <option key={env} value={`val:${env}`}>{env}</option>
              ))}
            </select>
            <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
          </div>
        </div>
      </div>

      {/* 知识库列表 */}
        <div
            className="bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-100 dark:border-slate-700 overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
          </div>
        ) : knowledgeBases.length === 0 ? (
          <div className="text-center py-20">
            <HardDrive className="w-16 h-16 text-slate-300 mx-auto mb-4" />
              <p className="text-slate-500 dark:text-slate-400">暂无知识库</p>
            <button
              onClick={onUpload}
              className="mt-4 text-primary-500 hover:text-primary-600"
            >
              上传第一个知识库
            </button>
          </div>
        ) : (
          <table className="w-full">
              <thead className="bg-slate-50 dark:bg-slate-700 border-b border-slate-100 dark:border-slate-600">
              <tr>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  名称
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  分类
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  服务
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  环境
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  大小
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  状态
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  提问
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  上传时间
                </th>
                  <th className="text-right px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              {knowledgeBases.map((kb, index) => (
                <motion.tr
                  key={kb.id}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: index * 0.05 }}
                  className="border-b border-slate-50 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
                >
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-3">
                      <FileText className="w-5 h-5 text-slate-400" />
                      <div>
                          <p className="font-medium text-slate-800 dark:text-white">{kb.name}</p>
                          <p className="text-xs text-slate-400 dark:text-slate-500">{kb.originalFilename}</p>
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <AnimatePresence mode="wait">
                      {editingCategoryId === kb.id ? (
                        <motion.div
                          key="editing"
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          className="flex items-center gap-2"
                        >
                          <input
                            ref={categoryInputRef}
                            type="text"
                            value={editingCategoryValue}
                            onChange={(e) => setEditingCategoryValue(e.target.value)}
                            onKeyDown={(e) => handleCategoryKeyDown(e, kb.id)}
                            placeholder="输入分类名称"
                            list="category-suggestions"
                            className="w-24 px-2 py-1 text-sm border border-primary-300 dark:border-primary-600 rounded focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                            disabled={savingCategory}
                          />
                          <datalist id="category-suggestions">
                            {categories.map((cat) => (
                              <option key={cat} value={cat} />
                            ))}
                          </datalist>
                          <button
                            onClick={() => handleSaveCategory(kb.id)}
                            disabled={savingCategory}
                            className="p-1 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-900/20 rounded transition-colors disabled:opacity-50"
                            title="保存"
                          >
                            {savingCategory ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <Check className="w-4 h-4" />
                            )}
                          </button>
                          <button
                            onClick={handleCancelEditCategory}
                            disabled={savingCategory}
                            className="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-600 rounded transition-colors disabled:opacity-50"
                            title="取消"
                          >
                            <X className="w-4 h-4" />
                          </button>
                        </motion.div>
                      ) : (
                        <motion.div
                          key="display"
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          className="flex items-center gap-2 group/category"
                        >
                          {kb.category ? (
                              <span
                                  className="px-2 py-1 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded text-sm">
                              {kb.category}
                            </span>
                          ) : (
                              <span className="text-slate-400 dark:text-slate-500 text-sm">未分类</span>
                          )}
                          <button
                            onClick={() => handleStartEditCategory(kb)}
                            className="p-1 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded opacity-0 group-hover/category:opacity-100 transition-all"
                            title="编辑分类"
                          >
                            <Edit3 className="w-3.5 h-3.5" />
                          </button>
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </td>
                  {/* 服务列 */}
                  <td className="px-6 py-4">
                    <AnimatePresence mode="wait">
                      {editingLabelsId === kb.id ? (
                        <motion.div
                          key="editing-service"
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          className="flex items-center gap-2"
                        >
                          <input
                            ref={serviceInputRef}
                            type="text"
                            value={editingService}
                            onChange={(e) => setEditingService(e.target.value)}
                            onKeyDown={(e) => handleLabelsKeyDown(e, kb.id)}
                            placeholder="服务"
                            maxLength={100}
                            list="service-suggestions"
                            className="w-24 px-2 py-1 text-sm border border-primary-300 dark:border-primary-600 rounded focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                            disabled={savingLabels}
                          />
                          <datalist id="service-suggestions">
                            {services.map((s) => (
                              <option key={s} value={s} />
                            ))}
                          </datalist>
                        </motion.div>
                      ) : (
                        <motion.div
                          key="display-service"
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          className="flex items-center gap-2 group/label"
                        >
                          {kb.service ? (
                            <span className="px-2 py-1 bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 rounded text-sm">
                              {kb.service}
                            </span>
                          ) : (
                            <span className="px-2 py-1 bg-slate-100 dark:bg-slate-700 text-slate-400 dark:text-slate-500 rounded text-sm">未分类</span>
                          )}
                          <button
                            onClick={() => handleStartEditLabels(kb)}
                            className="p-1 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded opacity-0 group-hover/label:opacity-100 transition-all"
                            title="编辑标签"
                          >
                            <Edit3 className="w-3.5 h-3.5" />
                          </button>
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </td>
                  {/* 环境列 */}
                  <td className="px-6 py-4">
                    <AnimatePresence mode="wait">
                      {editingLabelsId === kb.id ? (
                        <motion.div
                          key="editing-env"
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          className="flex items-center gap-2"
                        >
                          <input
                            type="text"
                            value={editingEnvironment}
                            onChange={(e) => setEditingEnvironment(e.target.value)}
                            onKeyDown={(e) => handleLabelsKeyDown(e, kb.id)}
                            placeholder="环境"
                            maxLength={50}
                            list="environment-suggestions"
                            className="w-24 px-2 py-1 text-sm border border-primary-300 dark:border-primary-600 rounded focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                            disabled={savingLabels}
                          />
                          <datalist id="environment-suggestions">
                            {environments.map((env) => (
                              <option key={env} value={env} />
                            ))}
                          </datalist>
                          <button
                            onClick={() => handleSaveLabels(kb.id)}
                            disabled={savingLabels}
                            className="p-1 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-900/20 rounded transition-colors disabled:opacity-50"
                            title="保存"
                          >
                            {savingLabels ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <Check className="w-4 h-4" />
                            )}
                          </button>
                          <button
                            onClick={handleCancelEditLabels}
                            disabled={savingLabels}
                            className="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-600 rounded transition-colors disabled:opacity-50"
                            title="取消"
                          >
                            <X className="w-4 h-4" />
                          </button>
                        </motion.div>
                      ) : (
                        <motion.div
                          key="display-env"
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                        >
                          {kb.environment ? (
                            <span className="px-2 py-1 bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400 rounded text-sm">
                              {kb.environment}
                            </span>
                          ) : (
                            <span className="px-2 py-1 bg-slate-100 dark:bg-slate-700 text-slate-400 dark:text-slate-500 rounded text-sm">未分类</span>
                          )}
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-600 dark:text-slate-300">
                    {formatFileSize(kb.fileSize)}
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <StatusIcon status={kb.vectorStatus} />
                        <span className="text-sm text-slate-600 dark:text-slate-300">
                        {getStatusText(kb.vectorStatus)}
                      </span>
                    </div>
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-600 dark:text-slate-300">
                    {kb.questionCount}
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-500 dark:text-slate-400">
                    {formatDate(kb.uploadedAt)}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex items-center justify-end gap-1">
                      {/* 下载按钮 */}
                      <button
                        onClick={() => handleDownload(kb)}
                        className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors"
                        title="下载"
                      >
                        <Download className="w-4 h-4" />
                      </button>
                      {/* 重新向量化按钮（仅 FAILED 状态显示） */}
                      {kb.vectorStatus === 'FAILED' && (
                        <button
                          onClick={() => handleRevectorize(kb.id)}
                          disabled={revectorizing === kb.id}
                          className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50"
                          title="重新向量化"
                        >
                          <RefreshCw className={`w-4 h-4 ${revectorizing === kb.id ? 'animate-spin' : ''}`} />
                        </button>
                      )}
                      {/* 删除按钮 */}
                      <button
                        onClick={() => setDeleteItem(kb)}
                        className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors"
                        title="删除"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem}
        itemType="知识库"
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteItem(null)}
      />
    </div>
  );
}
