import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  BookOpen,
  Plus,
  Clock,
  Server,
  Tag,
  Loader2,
  FileX,
} from 'lucide-react';
import { casesApi } from '../api/cases';
import type { CaseItem, CaseStatus } from '../types/cases';
import { ROUTES } from '../constants/routes';
import { formatDateOnly } from '../utils/date';
import { caseStatusLabels, caseStatusStyles } from '../utils/caseStatus';

const STATUS_OPTIONS: { value: CaseStatus | ''; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: caseStatusLabels.DRAFT },
  { value: 'PENDING_REVIEW', label: caseStatusLabels.PENDING_REVIEW },
  { value: 'PUBLISHED', label: caseStatusLabels.PUBLISHED },
  { value: 'REJECTED', label: caseStatusLabels.REJECTED },
  { value: 'DEPRECATED', label: caseStatusLabels.DEPRECATED },
];

export default function CasesPage() {
  const navigate = useNavigate();
  const [cases, setCases] = useState<CaseItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<string>('');

  const loadCases = useCallback(async () => {
    setLoading(true);
    try {
      const params: { status?: string } = {};
      if (statusFilter) params.status = statusFilter;
      const list = await casesApi.list(params);
      setCases(list);
    } catch (err) {
      console.error('加载案例列表失败', err);
    } finally {
      setLoading(false);
    }
  }, [statusFilter]);

  useEffect(() => {
    loadCases();
  }, [loadCases]);

  return (
    <div className="max-w-5xl mx-auto">
      {/* 页面标题 */}
      <motion.div
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="mb-8"
      >
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-amber-500 to-orange-600 flex items-center justify-center text-white shadow-lg shadow-amber-500/20">
              <BookOpen className="w-5 h-5" />
            </div>
            <div>
              <h1 className="text-2xl font-display font-bold text-slate-900 dark:text-white">
                故障案例库
              </h1>
              <p className="text-sm text-slate-500 dark:text-slate-400">
                从排查会话中沉淀可复用的故障解决方案
              </p>
            </div>
          </div>
          <motion.button
            onClick={() => navigate(ROUTES.chatSessions)}
            className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-xl hover:bg-primary-600 transition-colors text-sm font-medium"
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
          >
            <Plus className="w-4 h-4" />
            从会话创建
          </motion.button>
        </div>
      </motion.div>

      {/* 状态筛选 */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3, delay: 0.05 }}
        className="mb-6"
      >
        <div className="flex items-center gap-2">
          {STATUS_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setStatusFilter(opt.value)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                statusFilter === opt.value
                  ? 'bg-primary-500 text-white'
                  : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 border border-slate-200 dark:border-slate-700'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </motion.div>

      {/* 案例列表 */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3, delay: 0.1 }}
      >
        {loading ? (
          <div className="dark-card flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
          </div>
        ) : cases.length === 0 ? (
          <div className="dark-card text-center py-20">
            <FileX className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
            <p className="text-slate-500 dark:text-slate-400 mb-2">暂无案例</p>
            <p className="text-sm text-slate-400 dark:text-slate-500">
              在排查会话中点击"生成案例"可从 AI 回答中创建故障案例
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {cases.map((caseItem, index) => (
              <motion.div
                key={caseItem.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3, delay: index * 0.05 }}
                onClick={() => navigate(`/cases/${caseItem.id}`)}
                className="dark-card p-5 cursor-pointer hover:border-primary-500/50 transition-all group"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <h3 className="text-base font-semibold text-slate-800 dark:text-white truncate group-hover:text-primary-600 dark:group-hover:text-primary-400 transition-colors">
                        {caseItem.title}
                      </h3>
                      <span className={`px-2 py-0.5 rounded-full text-xs font-medium flex-shrink-0 ${caseStatusStyles[caseItem.status]}`}>
                        {caseStatusLabels[caseItem.status]}
                      </span>
                    </div>
                    {caseItem.problemDescription && (
                      <p className="text-sm text-slate-500 dark:text-slate-400 line-clamp-2 mb-2">
                        {caseItem.problemDescription}
                      </p>
                    )}
                    <div className="flex flex-wrap items-center gap-3">
                      <span className="inline-flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                        <Clock className="w-3.5 h-3.5" />
                        {formatDateOnly(caseItem.createdAt)}
                      </span>
                      {caseItem.service && (
                        <span className="inline-flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                          <Server className="w-3.5 h-3.5" />
                          {caseItem.service}
                        </span>
                      )}
                      {caseItem.environment && (
                        <span className="inline-flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                          <Tag className="w-3.5 h-3.5" />
                          {caseItem.environment}
                        </span>
                      )}
                      {caseItem.affectedVersions && (
                        <span className="text-xs text-slate-400 dark:text-slate-500">
                          版本: {caseItem.affectedVersions}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              </motion.div>
            ))}
          </div>
        )}
      </motion.div>
    </div>
  );
}
