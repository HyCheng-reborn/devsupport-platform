import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  ArrowLeft,
  Loader2,
  FileText,
  Save,
  Send,
  CheckCircle,
  XCircle,
  Archive,
  AlertCircle,
  RefreshCw,
} from 'lucide-react';
import { casesApi } from '../api/cases';
import type { CaseItem, CaseUpdateRequest } from '../types/cases';
import { ROUTES } from '../constants/routes';
import { caseStatusLabels, caseStatusStyles } from '../utils/caseStatus';

export default function CaseDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const numericId = Number(id);

  const [caseItem, setCaseItem] = useState<CaseItem | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [editData, setEditData] = useState<CaseUpdateRequest>({});
  const [isEditing, setIsEditing] = useState(false);

  const canEdit = caseItem && (caseItem.status === 'DRAFT' || caseItem.status === 'REJECTED');

  const loadCase = useCallback(async () => {
    if (!numericId || isNaN(numericId)) return;
    setLoading(true);
    try {
      const data = await casesApi.get(numericId);
      setCaseItem(data);
      setEditData({
        title: data.title,
        problemDescription: data.problemDescription || '',
        rootCause: data.rootCause || '',
        resolutionSteps: data.resolutionSteps || '',
        resolutionResult: data.resolutionResult || '',
        affectedVersions: data.affectedVersions || '',
        environment: data.environment || '',
        service: data.service || '',
        aiGeneratedContent: data.aiGeneratedContent || '',
        userConfirmedContent: data.userConfirmedContent || '',
      });
      setIsEditing(data.status === 'DRAFT' || data.status === 'REJECTED');
    } catch (err) {
      console.error('加载案例详情失败', err);
    } finally {
      setLoading(false);
    }
  }, [numericId]);

  useEffect(() => {
    loadCase();
  }, [loadCase]);

  const handleSave = async () => {
    if (!numericId || saving) return;
    setSaving(true);
    try {
      const updated = await casesApi.update(numericId, editData);
      setCaseItem(updated);
      setIsEditing(false);
    } catch (err) {
      console.error('保存案例失败', err);
    } finally {
      setSaving(false);
    }
  };

  const handleSubmit = async () => {
    if (!numericId || actionLoading) return;
    setActionLoading(true);
    try {
      const updated = await casesApi.submit(numericId);
      setCaseItem(updated);
    } catch (err) {
      console.error('提交审核失败', err);
    } finally {
      setActionLoading(false);
    }
  };

  const handleApprove = async () => {
    if (!numericId || actionLoading) return;
    setActionLoading(true);
    try {
      const updated = await casesApi.approve(numericId);
      setCaseItem(updated);
    } catch (err) {
      console.error('批准失败', err);
    } finally {
      setActionLoading(false);
    }
  };

  const handleReject = async () => {
    if (!numericId || actionLoading) return;
    setActionLoading(true);
    try {
      const updated = await casesApi.reject(numericId);
      setCaseItem(updated);
    } catch (err) {
      console.error('退回失败', err);
    } finally {
      setActionLoading(false);
    }
  };

  const handleDeprecate = async () => {
    if (!numericId || actionLoading) return;
    setActionLoading(true);
    try {
      const updated = await casesApi.deprecate(numericId);
      setCaseItem(updated);
    } catch (err) {
      console.error('废弃失败', err);
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
      </div>
    );
  }

  if (!caseItem) {
    return (
      <div className="max-w-3xl mx-auto text-center py-20">
        <FileText className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
        <p className="text-slate-500 dark:text-slate-400 mb-4">案例不存在或已被删除</p>
        <button
          onClick={() => navigate(ROUTES.cases)}
          className="text-primary-500 hover:text-primary-600 font-medium"
        >
          返回案例列表
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto">
      {/* 头部 */}
      <motion.div
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="mb-6"
      >
        <div className="flex items-center gap-4 mb-4">
          <button
            onClick={() => navigate(ROUTES.cases)}
            className="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <h1 className="text-xl font-display font-bold text-slate-900 dark:text-white truncate">
                {caseItem.title}
              </h1>
              <span className={`px-2 py-0.5 rounded-full text-xs font-medium flex-shrink-0 ${caseStatusStyles[caseItem.status]}`}>
                {caseStatusLabels[caseItem.status]}
              </span>
              {caseItem.status === 'PUBLISHED' && (
                <span
                  title="已发布案例自动纳入案例回归评测数据集"
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium flex-shrink-0 bg-teal-50 text-teal-700 dark:bg-teal-900/30 dark:text-teal-300"
                >
                  <RefreshCw className="w-3 h-3" />
                  已纳入回归评测
                </span>
              )}
            </div>
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
              创建于 {new Date(caseItem.createdAt).toLocaleDateString('zh-CN')} · 版本 {caseItem.versionNo}
            </p>
          </div>
        </div>

        {/* 操作按钮 */}
        <div className="flex items-center gap-3 ml-12">
          {canEdit && isEditing && (
            <motion.button
              onClick={handleSave}
              disabled={saving}
              className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 transition-colors text-sm font-medium disabled:opacity-50"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
            >
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
              保存
            </motion.button>
          )}
          {caseItem.status === 'DRAFT' && (
            <motion.button
              onClick={handleSubmit}
              disabled={actionLoading}
              className="flex items-center gap-2 px-4 py-2 bg-emerald-500 text-white rounded-lg hover:bg-emerald-600 transition-colors text-sm font-medium disabled:opacity-50"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
            >
              {actionLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
              提交审核
            </motion.button>
          )}
          {caseItem.status === 'PENDING_REVIEW' && (
            <>
              <motion.button
                onClick={handleApprove}
                disabled={actionLoading}
                className="flex items-center gap-2 px-4 py-2 bg-emerald-500 text-white rounded-lg hover:bg-emerald-600 transition-colors text-sm font-medium disabled:opacity-50"
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
              >
                {actionLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle className="w-4 h-4" />}
                批准
              </motion.button>
              <motion.button
                onClick={handleReject}
                disabled={actionLoading}
                className="flex items-center gap-2 px-4 py-2 bg-red-500 text-white rounded-lg hover:bg-red-600 transition-colors text-sm font-medium disabled:opacity-50"
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
              >
                {actionLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <XCircle className="w-4 h-4" />}
                退回
              </motion.button>
            </>
          )}
          {caseItem.status === 'PUBLISHED' && (
            <motion.button
              onClick={handleDeprecate}
              disabled={actionLoading}
              className="flex items-center gap-2 px-4 py-2 bg-slate-500 text-white rounded-lg hover:bg-slate-600 transition-colors text-sm font-medium disabled:opacity-50"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
            >
              {actionLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Archive className="w-4 h-4" />}
              废弃
            </motion.button>
          )}
          {caseItem.status === 'REJECTED' && (
            <motion.button
              onClick={handleSubmit}
              disabled={actionLoading}
              className="flex items-center gap-2 px-4 py-2 bg-emerald-500 text-white rounded-lg hover:bg-emerald-600 transition-colors text-sm font-medium disabled:opacity-50"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
            >
              {actionLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
              重新提交
            </motion.button>
          )}
        </div>
      </motion.div>

      {/* 内容区域 */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3, delay: 0.1 }}
        className="space-y-6"
      >
        {/* 基本信息 */}
        <div className="dark-card p-6">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-white mb-4">基本信息</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-slate-600 dark:text-slate-400 mb-1">服务</label>
              {isEditing ? (
                <input
                  type="text"
                  value={editData.service || ''}
                  onChange={(e) => setEditData({ ...editData, service: e.target.value })}
                  className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm"
                  placeholder="如: payment-service"
                />
              ) : (
                <p className="text-sm text-slate-700 dark:text-slate-300">{caseItem.service || '—'}</p>
              )}
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-600 dark:text-slate-400 mb-1">环境</label>
              {isEditing ? (
                <input
                  type="text"
                  value={editData.environment || ''}
                  onChange={(e) => setEditData({ ...editData, environment: e.target.value })}
                  className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm"
                  placeholder="如: production"
                />
              ) : (
                <p className="text-sm text-slate-700 dark:text-slate-300">{caseItem.environment || '—'}</p>
              )}
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-600 dark:text-slate-400 mb-1">影响版本</label>
              {isEditing ? (
                <input
                  type="text"
                  value={editData.affectedVersions || ''}
                  onChange={(e) => setEditData({ ...editData, affectedVersions: e.target.value })}
                  className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm"
                  placeholder="如: v2.1.0"
                />
              ) : (
                <p className="text-sm text-slate-700 dark:text-slate-300">{caseItem.affectedVersions || '—'}</p>
              )}
            </div>
          </div>
        </div>

        {/* 问题描述 */}
        <div className="dark-card p-6">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-white mb-4">问题描述</h2>
          {isEditing ? (
            <textarea
              value={editData.problemDescription || ''}
              onChange={(e) => setEditData({ ...editData, problemDescription: e.target.value })}
              rows={4}
              className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm resize-none"
              placeholder="详细描述遇到的问题..."
            />
          ) : (
            <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
              {caseItem.problemDescription || '—'}
            </p>
          )}
        </div>

        {/* 根因分析 */}
        <div className="dark-card p-6">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-white mb-4">根因分析</h2>
          {isEditing ? (
            <textarea
              value={editData.rootCause || ''}
              onChange={(e) => setEditData({ ...editData, rootCause: e.target.value })}
              rows={4}
              className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm resize-none"
              placeholder="分析问题根本原因..."
            />
          ) : (
            <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
              {caseItem.rootCause || '—'}
            </p>
          )}
        </div>

        {/* 解决步骤 */}
        <div className="dark-card p-6">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-white mb-4">解决步骤</h2>
          {isEditing ? (
            <textarea
              value={editData.resolutionSteps || ''}
              onChange={(e) => setEditData({ ...editData, resolutionSteps: e.target.value })}
              rows={6}
              className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm resize-none"
              placeholder="详细的解决步骤..."
            />
          ) : (
            <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
              {caseItem.resolutionSteps || '—'}
            </p>
          )}
        </div>

        {/* 解决结果 */}
        <div className="dark-card p-6">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-white mb-4">解决结果</h2>
          {isEditing ? (
            <textarea
              value={editData.resolutionResult || ''}
              onChange={(e) => setEditData({ ...editData, resolutionResult: e.target.value })}
              rows={3}
              className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm resize-none"
              placeholder="最终解决结果..."
            />
          ) : (
            <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
              {caseItem.resolutionResult || '—'}
            </p>
          )}
        </div>

        {/* AI 生成内容 */}
        {caseItem.aiGeneratedContent && (
          <div className="dark-card p-6 border-l-4 border-l-blue-500 bg-blue-50/50 dark:bg-blue-900/10">
            <div className="flex items-center gap-2 mb-3">
              <AlertCircle className="w-5 h-5 text-blue-500" />
              <h2 className="text-lg font-semibold text-blue-700 dark:text-blue-400">AI 生成内容</h2>
            </div>
            {isEditing ? (
              <textarea
                value={editData.aiGeneratedContent || ''}
                onChange={(e) => setEditData({ ...editData, aiGeneratedContent: e.target.value })}
                rows={8}
                className="w-full px-3 py-2 border border-blue-200 dark:border-blue-700 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm resize-none"
              />
            ) : (
              <p className="text-sm text-blue-800 dark:text-blue-300 whitespace-pre-wrap">
                {caseItem.aiGeneratedContent}
              </p>
            )}
          </div>
        )}

        {/* 用户确认内容 */}
        {caseItem.userConfirmedContent && (
          <div className="dark-card p-6">
            <h2 className="text-lg font-semibold text-slate-800 dark:text-white mb-3">用户确认内容</h2>
            {isEditing ? (
              <textarea
                value={editData.userConfirmedContent || ''}
                onChange={(e) => setEditData({ ...editData, userConfirmedContent: e.target.value })}
                rows={8}
                className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm resize-none"
              />
            ) : (
              <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                {caseItem.userConfirmedContent}
              </p>
            )}
          </div>
        )}
      </motion.div>
    </div>
  );
}
