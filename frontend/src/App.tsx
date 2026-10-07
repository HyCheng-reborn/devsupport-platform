import { BrowserRouter, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import Layout from './components/Layout';
import { Suspense, lazy } from 'react';
import type { UploadKnowledgeBaseResponse } from './api/knowledgebase';
import { ROUTE_PATTERNS, ROUTES } from './constants/routes';

// Lazy load components
const DocsCenterPage = lazy(() => import('./pages/DocsCenterPage'));
const ChatSessionsPage = lazy(() => import('./pages/ChatSessionsPage'));
const ChatSessionDetailPage = lazy(() => import('./pages/ChatSessionDetailPage'));
const CasesPage = lazy(() => import('./pages/CasesPage'));
const CaseDetailPage = lazy(() => import('./pages/CaseDetailPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const EvalResultsPage = lazy(() => import('./pages/EvalResultsPage'));
const KnowledgeBaseUploadPage = lazy(() => import('./pages/KnowledgeBaseUploadPage'));

// Loading component
const Loading = () => (
  <div className="flex items-center justify-center min-h-[50vh]">
    <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
  </div>
);

function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/" element={<Layout />}>
            {/* 默认重定向到文档中心 */}
            <Route index element={<Navigate to={ROUTES.docsCenter} replace />} />

            {/* 文档中心 */}
            <Route path="docs" element={<DocsCenterPage />} />

            {/* 文档上传 */}
            <Route path="docs/upload" element={<DocsUploadPageWrapper />} />

            {/* 排查会话 */}
            <Route path="chat" element={<ChatSessionsPage />} />
            <Route path={ROUTE_PATTERNS.chatSessionDetail} element={<ChatSessionDetailPage />} />

            {/* 案例库 */}
            <Route path="cases" element={<CasesPage />} />
            <Route path="cases/:id" element={<CaseDetailPage />} />

            {/* 评测结果 */}
            <Route path="eval-results" element={<EvalResultsPage />} />

            {/* 设置 */}
            <Route path="settings" element={<SettingsPage />} />

            {/* ===== 旧路由重定向 ===== */}

            {/* 知识库旧路由 -> 文档中心 */}
            <Route path="knowledgebase" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="knowledgebase/upload" element={<Navigate to={ROUTES.docsUpload} replace />} />
            <Route path="knowledgebase/chat" element={<Navigate to={ROUTES.chatSessions} replace />} />

            {/* 面试相关旧路由重定向到文档中心 */}
            <Route path="history" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="history/:resumeId" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="upload" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="interview-hub" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="interviews" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="interviews/:sessionId" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path={ROUTES.interview.slice(1)} element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path={ROUTE_PATTERNS.interviewCreate} element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path={ROUTE_PATTERNS.interviewSession} element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="interview/*" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="voice-interview" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="voice-interview/:sessionId/evaluation" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="interview-schedule" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="knowledgebase-interview" element={<Navigate to={ROUTES.docsCenter} replace />} />
            <Route path="knowledgebase-interview/*" element={<Navigate to={ROUTES.docsCenter} replace />} />
          </Route>
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}

// 文档上传页面包装器（复用 KnowledgeBaseUploadPage）
function DocsUploadPageWrapper() {
  const navigate = useNavigate();

  const handleUploadComplete = (_result: UploadKnowledgeBaseResponse) => {
    navigate(ROUTES.docsCenter);
  };

  const handleBack = () => {
    navigate(ROUTES.docsCenter);
  };

  return <KnowledgeBaseUploadPage onUploadComplete={handleUploadComplete} onBack={handleBack} />;
}

export default App;
