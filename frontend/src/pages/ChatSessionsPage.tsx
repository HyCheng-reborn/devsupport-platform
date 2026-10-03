import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  MessageSquare,
  Plus,
  Clock,
  Layers,
  Loader2,
} from 'lucide-react';
import { ragChatApi, type RagChatSessionListItem } from '../api/ragChat';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import ContextSelector from '../components/ContextSelector';
import { ROUTES } from '../constants/routes';
import { formatDateOnly } from '../utils/date';

export default function ChatSessionsPage() {
  const navigate = useNavigate();
  const [sessions, setSessions] = useState<RagChatSessionListItem[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [loadingSessions, setLoadingSessions] = useState(true);
  const [loadingKbs, setLoadingKbs] = useState(true);
  const [selectedKbIds, setSelectedKbIds] = useState<Set<number>>(new Set());
  const [showKbPicker, setShowKbPicker] = useState(false);
  const [creating, setCreating] = useState(false);
  const [contextService, setContextService] = useState<string>('');
  const [contextEnvironment, setContextEnvironment] = useState<string>('');

  const loadSessions = useCallback(async () => {
    setLoadingSessions(true);
    try {
      const list = await ragChatApi.listSessions();
      setSessions(list);
    } catch (err) {
      console.error('加载会话列表失败', err);
    } finally {
      setLoadingSessions(false);
    }
  }, []);

  const loadKnowledgeBases = useCallback(async () => {
    setLoadingKbs(true);
    try {
      const list = await knowledgeBaseApi.getAllKnowledgeBases('time', 'COMPLETED');
      setKnowledgeBases(list);
    } catch (err) {
      console.error('加载知识库列表失败', err);
    } finally {
      setLoadingKbs(false);
    }
  }, []);

  useEffect(() => {
    loadSessions();
    loadKnowledgeBases();
  }, [loadSessions, loadKnowledgeBases]);

  const handleToggleKb = (kbId: number) => {
    setSelectedKbIds((prev) => {
      const next = new Set(prev);
      if (next.has(kbId)) {
        next.delete(kbId);
      } else {
        next.add(kbId);
      }
      return next;
    });
  };

  const handleCreateSession = async () => {
    if (selectedKbIds.size === 0 || creating) return;
    setCreating(true);
    try {
      const session = await ragChatApi.createSession(
        Array.from(selectedKbIds),
        undefined,
        contextService || undefined,
        contextEnvironment || undefined
      );
      setShowKbPicker(false);
      setSelectedKbIds(new Set());
      navigate(ROUTES.chatSessionDetail(String(session.id)));
    } catch (err) {
      console.error('创建会话失败', err);
    } finally {
      setCreating(false);
    }
  };

  const formatTimeAgo = (dateStr: string): string => {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    if (hours < 24) return `${hours} 小时前`;
    if (days < 7) return `${days} 天前`;
    return formatDateOnly(dateStr);
  };

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
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-blue-600 flex items-center justify-center text-white shadow-lg shadow-blue-500/20">
              <MessageSquare className="w-5 h-5" />
            </div>
            <div>
              <h1 className="text-2xl font-display font-bold text-slate-900 dark:text-white">
                排查会话
              </h1>
              <p className="text-sm text-slate-500 dark:text-slate-400">
                基于知识库的 RAG 排查对话
              </p>
            </div>
          </div>
          <motion.button
            onClick={() => setShowKbPicker(true)}
            className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-xl hover:bg-primary-600 transition-colors text-sm font-medium"
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
          >
            <Plus className="w-4 h-4" />
            新建排查会话
          </motion.button>
        </div>
      </motion.div>

      {/* 上下文范围选择器 */}
      <ContextSelector
        onScopeChange={(kbIds, service, environment) => {
          setContextService(service || '');
          setContextEnvironment(environment || '');
          // 如果有上下文解析出的 KB，自动选中
          if (kbIds.length > 0) {
            setSelectedKbIds(new Set(kbIds));
          }
        }}
      />

      {/* 新建会话 — 知识库选择弹窗 */}
      {showKbPicker && (
        <motion.div
          initial={{ opacity: 0, height: 0 }}
          animate={{ opacity: 1, height: 'auto' }}
          className="dark-card p-6 mb-6"
        >
          <h3 className="text-base font-semibold text-slate-800 dark:text-white mb-4">
            选择关联知识库
          </h3>
          {loadingKbs ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
            </div>
          ) : knowledgeBases.length === 0 ? (
            <p className="text-sm text-slate-500 dark:text-slate-400 py-4 text-center">
              暂无已完成向量化的知识库，请先上传文档。
            </p>
          ) : (
            <>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 max-h-60 overflow-y-auto">
                {knowledgeBases.map((kb) => (
                  <label
                    key={kb.id}
                    className={`flex items-center gap-3 p-3 rounded-lg cursor-pointer transition-all border ${
                      selectedKbIds.has(kb.id)
                        ? 'bg-primary-50 dark:bg-primary-900/30 border-primary-500'
                        : 'bg-slate-50 dark:bg-slate-700/50 border-transparent hover:bg-slate-100 dark:hover:bg-slate-700'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={selectedKbIds.has(kb.id)}
                      onChange={() => handleToggleKb(kb.id)}
                      className="w-4 h-4 text-primary-500 rounded focus:ring-primary-500"
                    />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-slate-800 dark:text-white truncate">
                        {kb.name}
                      </p>
                      <div className="flex items-center gap-2 mt-0.5">
                        {kb.service && (
                          <span className="text-[10px] px-1.5 py-0.5 bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 rounded">
                            {kb.service}
                          </span>
                        )}
                        {kb.environment && (
                          <span className="text-[10px] px-1.5 py-0.5 bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400 rounded">
                            {kb.environment}
                          </span>
                        )}
                      </div>
                    </div>
                  </label>
                ))}
              </div>
              <div className="flex justify-end gap-3 mt-4 pt-4 border-t border-slate-100 dark:border-slate-700">
                <button
                  onClick={() => {
                    setShowKbPicker(false);
                    setSelectedKbIds(new Set());
                  }}
                  className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white transition-colors"
                >
                  取消
                </button>
                <button
                  onClick={handleCreateSession}
                  disabled={selectedKbIds.size === 0 || creating}
                  className="px-4 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                >
                  {creating && <Loader2 className="w-4 h-4 animate-spin" />}
                  创建会话
                </button>
              </div>
            </>
          )}
        </motion.div>
      )}

      {/* 会话列表 */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3, delay: 0.1 }}
      >
        {loadingSessions ? (
          <div className="dark-card flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
          </div>
        ) : sessions.length === 0 ? (
          <div className="dark-card text-center py-20">
            <MessageSquare className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
            <p className="text-slate-500 dark:text-slate-400 mb-2">暂无排查会话</p>
            <p className="text-sm text-slate-400 dark:text-slate-500">
              点击"新建排查会话"开始一次基于知识库的排查对话
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {sessions.map((session, index) => (
              <motion.div
                key={session.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3, delay: index * 0.05 }}
                onClick={() => navigate(ROUTES.chatSessionDetail(String(session.id)))}
                className="dark-card p-5 cursor-pointer hover:border-primary-500/50 transition-all group"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="flex-1 min-w-0">
                    <h3 className="text-base font-semibold text-slate-800 dark:text-white truncate group-hover:text-primary-600 dark:group-hover:text-primary-400 transition-colors">
                      {session.title}
                    </h3>
                    <div className="flex flex-wrap items-center gap-3 mt-2">
                      <span className="inline-flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                        <Clock className="w-3.5 h-3.5" />
                        {formatTimeAgo(session.updatedAt)}
                      </span>
                      <span className="inline-flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                        <MessageSquare className="w-3.5 h-3.5" />
                        {session.messageCount} 条消息
                      </span>
                      {session.knowledgeBaseNames.length > 0 && (
                        <span className="inline-flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                          <Layers className="w-3.5 h-3.5" />
                          {session.knowledgeBaseNames.join(', ')}
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
