import { request } from './request.ts';
import { streamSse } from './stream.ts';
import { parseDoneStatus, resolveFinalStatus, type MessageStatus } from './ragStreamStatus.ts';

// ========== 类型定义 ==========

export interface RagChatSession {
  id: number;
  title: string;
  knowledgeBaseIds: number[];
  createdAt: string;
}

export interface RagChatSessionListItem {
  id: number;
  title: string;
  messageCount: number;
  knowledgeBaseNames: string[];
  updatedAt: string;
  isPinned: boolean;
}

export interface SourceReference {
  kbId: number;
  documentName: string;
  contentSnippet: string;
  score: number | null;
}

export type { MessageStatus };

export interface RagChatMessage {
  id: number;
  type: 'user' | 'assistant';
  content: string;
  sourcesJson?: string;
  status?: MessageStatus;
  createdAt: string;
}

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
}

export interface RagChatSessionDetail {
  id: number;
  title: string;
  knowledgeBases: KnowledgeBaseItem[];
  messages: RagChatMessage[];
  createdAt: string;
  updatedAt: string;
}

// ========== API 函数 ==========

export const ragChatApi = {
  /**
   * 创建新会话
   */
  async createSession(knowledgeBaseIds: number[], title?: string): Promise<RagChatSession> {
    return request.post<RagChatSession>('/api/rag-chat/sessions', {
      knowledgeBaseIds,
      title,
    });
  },

  /**
   * 获取会话列表
   */
  async listSessions(): Promise<RagChatSessionListItem[]> {
    return request.get<RagChatSessionListItem[]>('/api/rag-chat/sessions');
  },

  /**
   * 获取会话详情
   */
  async getSessionDetail(sessionId: number): Promise<RagChatSessionDetail> {
    return request.get<RagChatSessionDetail>(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 更新会话标题
   */
  async updateSessionTitle(sessionId: number, title: string): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/title`, { title });
  },

  /**
   * 更新会话知识库
   */
  async updateKnowledgeBases(sessionId: number, knowledgeBaseIds: number[]): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/knowledge-bases`, {
      knowledgeBaseIds,
    });
  },

  /**
   * 切换会话置顶状态
   */
  async togglePin(sessionId: number): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/pin`);
  },

  /**
   * 删除会话
   */
  async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 发送消息（流式SSE）
   * onComplete 回传服务端确认的最终状态；done 未到达或状态不可识别时按 MODEL_FAILED 处理，
   * 不会无条件当作 COMPLETED。
   */
  async sendMessageStream(
    sessionId: number,
    question: string,
    onMessage: (chunk: string) => void,
    onSources: (sourcesJson: string) => void,
    onComplete: (status?: MessageStatus) => void,
    onError: (error: Error) => void
  ): Promise<void> {
    let finalized = false;
    return streamSse({
      url: `/api/rag-chat/sessions/${sessionId}/messages/stream`,
      init: {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
      },
      onMessage,
      onSources: (data) => onSources(data),
      onDone: (rawStatus) => {
        if (finalized) return;
        finalized = true;
        onComplete(resolveFinalStatus(parseDoneStatus(rawStatus)));
      },
      onComplete: () => {
        // 流结束但没有 done 事件 = 服务端未确认成功（异常终止 / 持久化失败）
        if (finalized) return;
        finalized = true;
        onComplete('MODEL_FAILED');
      },
      onError: (error) => {
        if (finalized) return;
        finalized = true;
        onError(error);
      },
      parseMode: 'event',
      trimDataPrefixSpace: false,
      unescapeEscapedNewlines: true,
      dataJoiner: '',
    });
  },
};
