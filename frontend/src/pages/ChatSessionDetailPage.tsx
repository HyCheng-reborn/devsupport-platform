import { useCallback, useEffect, useRef, useState, useTransition } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Virtuoso, type VirtuosoHandle } from 'react-virtuoso';
import {
  AlertTriangle,
  ArrowLeft,
  Edit,
  Loader2,
  MessageSquare,
} from 'lucide-react';
import {
  ragChatApi,
  type RagChatSessionDetail,
  type SourceReference,
  type MessageStatus,
} from '../api/ragChat';
import { selectSourcesForStatus, sourcesDisplayMode } from '../api/ragStreamStatus';
import { toSourceTagView } from '../utils/sourceDisplay';
import CodeBlock from '../components/CodeBlock';
import { ROUTES } from '../constants/routes';

interface Message {
  id?: number;
  type: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  sources?: SourceReference[];
  status?: MessageStatus;
}

export default function ChatSessionDetailPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const numericSessionId = Number(sessionId);

  const [session, setSession] = useState<RagChatSessionDetail | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [question, setQuestion] = useState('');
  const [editingTitle, setEditingTitle] = useState(false);
  const [newTitle, setNewTitle] = useState('');

  const virtuosoRef = useRef<VirtuosoHandle>(null);
  const rafRef = useRef<number>();
  const [, startTransition] = useTransition();

  const loadSession = useCallback(async () => {
    if (!numericSessionId || isNaN(numericSessionId)) return;
    setLoading(true);
    try {
      const detail = await ragChatApi.getSessionDetail(numericSessionId);
      setSession(detail);
      setMessages(
        detail.messages.map((m) => ({
          id: m.id,
          type: m.type,
          content: m.content,
          timestamp: new Date(m.createdAt),
          sources: m.sourcesJson
            ? (() => {
                try {
                  return JSON.parse(m.sourcesJson) as SourceReference[];
                } catch {
                  return undefined;
                }
              })()
            : undefined,
          status: m.status,
        })),
      );
    } catch (err) {
      console.error('加载会话详情失败', err);
    } finally {
      setLoading(false);
    }
  }, [numericSessionId]);

  useEffect(() => {
    loadSession();
  }, [loadSession]);

  const formatMarkdown = (text: string): string => {
    if (!text) return '';
    return text
      .replace(/\\n/g, '\n')
      .replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2')
      .replace(/^(\s*)(\d+)\.([^\s\n])/gm, '$1$2. $3')
      .replace(/^(\s*[-*])([^\s\n-])/gm, '$1 $2')
      .replace(/\n{3,}/g, '\n\n');
  };

  const handleSubmit = async () => {
    if (!question.trim() || sending || !numericSessionId) return;

    const userQuestion = question.trim();
    setQuestion('');
    setSending(true);

    const userMessage: Message = {
      type: 'user',
      content: userQuestion,
      timestamp: new Date(),
    };
    setMessages((prev) => [...prev, userMessage]);

    const assistantMsgId = Date.now();
    setMessages((prev) => [
      ...prev,
      {
        id: assistantMsgId,
        type: 'assistant',
        content: '',
        timestamp: new Date(),
      },
    ]);

    let fullContent = '';
    let currentSources: SourceReference[] = [];

    const updateAssistantMessage = (content: string) => {
      setMessages((prev) => {
        const newMessages = [...prev];
        const lastIndex = newMessages.length - 1;
        if (lastIndex >= 0 && newMessages[lastIndex].type === 'assistant') {
          newMessages[lastIndex] = { ...newMessages[lastIndex], content };
        }
        return newMessages;
      });
    };

    try {
      await ragChatApi.sendMessageStream(
        numericSessionId,
        userQuestion,
        (chunk: string) => {
          fullContent += chunk;
          if (rafRef.current) cancelAnimationFrame(rafRef.current);
          rafRef.current = requestAnimationFrame(() => {
            startTransition(() => {
              updateAssistantMessage(fullContent);
            });
          });
        },
        (sourcesJson: string) => {
          try {
            currentSources = JSON.parse(sourcesJson) as SourceReference[];
          } catch {
            currentSources = [];
          }
        },
        (status?: MessageStatus) => {
          const finalStatus = status ?? 'MODEL_FAILED';
          const sourcesForMessage = selectSourcesForStatus(currentSources, finalStatus);
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsgId
                ? { ...m, content: fullContent, sources: sourcesForMessage, status: finalStatus }
                : m,
            ),
          );
          setSending(false);
          loadSession();
        },
        (error: Error) => {
          console.error('流式查询失败:', error);
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsgId
                ? {
                    ...m,
                    content: fullContent || error.message,
                    sources: currentSources,
                    status: 'MODEL_FAILED' as MessageStatus,
                  }
                : m,
            ),
          );
          setSending(false);
        },
      );
    } catch (err) {
      console.error('发起流式查询失败:', err);
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantMsgId
            ? {
                ...m,
                content: err instanceof Error ? err.message : '回答失败，请重试',
                status: 'MODEL_FAILED' as MessageStatus,
              }
            : m,
        ),
      );
      setSending(false);
    }
  };

  const handleSaveTitle = async () => {
    if (!newTitle.trim() || !numericSessionId) return;
    try {
      await ragChatApi.updateSessionTitle(numericSessionId, newTitle.trim());
      setSession((prev) => (prev ? { ...prev, title: newTitle.trim() } : prev));
      setEditingTitle(false);
      setNewTitle('');
    } catch (err) {
      console.error('更新标题失败', err);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
      </div>
    );
  }

  if (!session) {
    return (
      <div className="max-w-3xl mx-auto text-center py-20">
        <MessageSquare className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
        <p className="text-slate-500 dark:text-slate-400 mb-4">会话不存在或已被删除</p>
        <button
          onClick={() => navigate(ROUTES.chatSessions)}
          className="text-primary-500 hover:text-primary-600 font-medium"
        >
          返回会话列表
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto flex flex-col h-[calc(100vh-5rem)]">
      {/* 头部 */}
      <motion.div
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
        className="flex items-center gap-4 mb-4 flex-shrink-0"
      >
        <button
          onClick={() => navigate(ROUTES.chatSessions)}
          className="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1 min-w-0">
          {editingTitle ? (
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={newTitle}
                onChange={(e) => setNewTitle(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleSaveTitle();
                  if (e.key === 'Escape') {
                    setEditingTitle(false);
                    setNewTitle('');
                  }
                }}
                className="flex-1 px-3 py-1 text-lg font-semibold border border-primary-300 dark:border-primary-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                autoFocus
              />
              <button
                onClick={handleSaveTitle}
                className="px-3 py-1 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600"
              >
                保存
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <h1 className="text-xl font-display font-bold text-slate-900 dark:text-white truncate">
                {session.title}
              </h1>
              <button
                onClick={() => {
                  setEditingTitle(true);
                  setNewTitle(session.title);
                }}
                className="p-1 text-slate-400 hover:text-primary-500 rounded transition-colors"
              >
                <Edit className="w-4 h-4" />
              </button>
            </div>
          )}
          {session.knowledgeBases.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mt-1">
              {session.knowledgeBases.map((kb) => (
                <span
                  key={kb.id}
                  className="px-2 py-0.5 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 text-xs rounded-full"
                >
                  {kb.name}
                </span>
              ))}
            </div>
          )}
        </div>
      </motion.div>

      {/* 消息列表 */}
      <div className="flex-1 min-h-0 dark-card rounded-2xl overflow-hidden flex flex-col">
        {messages.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center text-slate-400 dark:text-slate-500">
            <MessageSquare className="w-12 h-12 mb-3 opacity-50" />
            <p className="text-sm">开始提问吧！</p>
          </div>
        ) : (
          <Virtuoso
            ref={virtuosoRef}
            data={messages}
            initialTopMostItemIndex={messages.length - 1}
            followOutput="smooth"
            className="h-full w-full"
            itemContent={(_index, msg) => (
              <div className="pb-4 px-4 first:pt-4">
                <motion.div
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  className={`flex ${msg.type === 'user' ? 'justify-end' : 'justify-start'}`}
                >
                  <div
                    className={`max-w-[85%] rounded-2xl p-4 shadow-sm ${
                      msg.type === 'user'
                        ? 'bg-primary-600 text-white'
                        : 'bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-600 text-slate-800 dark:text-slate-100'
                    }`}
                  >
                    {msg.type === 'user' ? (
                      <p className="whitespace-pre-wrap leading-relaxed text-sm">{msg.content}</p>
                    ) : (
                      <div className="prose prose-slate dark:prose-invert prose-sm max-w-none">
                        <ReactMarkdown
                          remarkPlugins={[remarkGfm]}
                          components={{
                            code: ({ className, children }) => {
                              const match = /language-(\w+)/.exec(className || '');
                              const isInline = !match;
                              if (isInline) {
                                return (
                                  <code className="bg-slate-100 dark:bg-slate-600 text-primary-600 dark:text-primary-400 px-1.5 py-0.5 rounded-md text-sm font-normal">
                                    {children}
                                  </code>
                                );
                              }
                              return (
                                <CodeBlock language={match[1]}>
                                  {String(children).replace(/\n$/, '')}
                                </CodeBlock>
                              );
                            },
                            pre: ({ children }) => <>{children}</>,
                          }}
                        >
                          {formatMarkdown(msg.content)}
                        </ReactMarkdown>
                        {sending && _index === messages.length - 1 && (
                          <span className="inline-block w-0.5 h-5 bg-primary-500 ml-1 animate-pulse" />
                        )}
                        {msg.sources && msg.sources.length > 0 && (() => {
                          const mode = sourcesDisplayMode(msg.status);
                          if (mode !== 'grounded' && mode !== 'degraded') return null;
                          const isGrounded = mode === 'grounded';
                          // 检测是否存在多个不同 versionLabel（非空）
                          const uniqueVersions = Array.from(
                            new Set(
                              msg.sources!
                                .map((s) => s.versionLabel?.trim())
                                .filter((v): v is string => !!v)
                            )
                          );
                          const hasMultipleVersions = uniqueVersions.length > 1;
                          return (
                            <div
                              className={`mt-2 border-t pt-2 ${
                                isGrounded
                                  ? 'border-gray-200 dark:border-slate-600'
                                  : 'border-amber-200 dark:border-amber-700/50'
                              }`}
                            >
                              {hasMultipleVersions && (
                                <div className="mb-2 flex items-center gap-2 rounded-md bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700/50 px-3 py-2">
                                  <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0" />
                                  <span className="text-xs text-amber-700 dark:text-amber-400">
                                    本次回答引用了多个版本文档，请注意版本适用性
                                  </span>
                                </div>
                              )}
                              <div className="text-xs text-gray-500 dark:text-slate-400 mb-1">
                                {isGrounded ? '引用来源：' : '检索到的参考文档（仅供参考）：'}
                              </div>
                              {msg.sources.slice(0, 5).map((src, i) => {
                                const view = toSourceTagView(src);
                                return (
                                  <details key={i} className="mb-1 text-xs">
                                    <summary
                                      className={`cursor-pointer ${
                                        isGrounded
                                          ? 'text-blue-600 hover:text-blue-800 dark:text-blue-400'
                                          : 'text-amber-600 hover:text-amber-800 dark:text-amber-400'
                                      }`}
                                    >
                                      <span className="inline-flex items-center gap-1.5 flex-wrap">
                                        <span>
                                          {view.documentName}
                                          {view.sectionTitle && (
                                            <>
                                              <span className="text-gray-400 dark:text-slate-500 mx-0.5">&gt;</span>
                                              <span className="text-slate-600 dark:text-slate-300">{view.sectionTitle}</span>
                                            </>
                                          )}
                                        </span>
                                        {view.serviceName && (
                                          <span className="px-1 py-0.5 bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 rounded text-[10px]">
                                            {view.serviceName}
                                          </span>
                                        )}
                                        {view.environmentName && (
                                          <span className="px-1 py-0.5 bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400 rounded text-[10px]">
                                            {view.environmentName}
                                          </span>
                                        )}
                                        {view.versionTag && (
                                          <span className="px-1 py-0.5 bg-purple-50 dark:bg-purple-900/30 text-purple-600 dark:text-purple-400 rounded text-[10px]">
                                            {view.versionTag}
                                          </span>
                                        )}
                                        {view.scoreLabel && (
                                          <span className="text-gray-400">({view.scoreLabel})</span>
                                        )}
                                      </span>
                                    </summary>
                                    <p className="mt-1 text-gray-600 dark:text-slate-400 pl-3 whitespace-pre-wrap">
                                      {view.contentSnippet}
                                    </p>
                                  </details>
                                );
                              })}
                            </div>
                          );
                        })()}
                        {msg.status === 'INSUFFICIENT_INFO' && (
                          <div className="mt-2 flex items-center gap-2 rounded-md bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-700/50 px-3 py-2">
                            <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0" />
                            <span className="text-xs text-amber-700 dark:text-amber-400">
                              信息不足，建议补充更多细节或检查相关文档
                            </span>
                          </div>
                        )}
                        {msg.status && msg.status !== 'COMPLETED' && msg.status !== 'INSUFFICIENT_INFO' && (
                          <div className="mt-1 text-xs">
                            {msg.status === 'NO_RESULTS' && (
                              <span className="text-yellow-500">未找到相关文档</span>
                            )}
                            {msg.status === 'MODEL_FAILED' && (
                              <span className="text-red-500">回答生成失败</span>
                            )}
                            {msg.status === 'CLIENT_DISCONNECTED' && (
                              <span className="text-gray-400">回答中断</span>
                            )}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </motion.div>
              </div>
            )}
          />
        )}

        {/* 输入区域 */}
        <div className="p-4 border-t border-slate-200 dark:border-slate-600 flex-shrink-0">
          <div className="flex gap-3">
            <input
              type="text"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) handleSubmit();
              }}
              placeholder="输入您的问题..."
              className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
              disabled={sending}
            />
            <motion.button
              onClick={handleSubmit}
              disabled={!question.trim() || sending}
              className="px-5 py-2.5 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-all disabled:opacity-50 disabled:cursor-not-allowed text-sm"
              whileHover={{ scale: sending ? 1 : 1.02 }}
              whileTap={{ scale: sending ? 1 : 0.98 }}
            >
              发送
            </motion.button>
          </div>
        </div>
      </div>
    </div>
  );
}
