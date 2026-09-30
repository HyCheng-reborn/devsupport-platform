import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import Layout from './components/Layout';
import { Suspense, lazy } from 'react';
import type { UploadKnowledgeBaseResponse } from './api/knowledgebase';
import { ROUTE_PATTERNS, ROUTES } from './constants/routes';

// Lazy load components
const KnowledgeBaseQueryPage = lazy(() => import('./pages/KnowledgeBaseQueryPage'));
const KnowledgeBaseUploadPage = lazy(() => import('./pages/KnowledgeBaseUploadPage'));
const KnowledgeBaseManagePage = lazy(() => import('./pages/KnowledgeBaseManagePage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));

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
            {/* 默认重定向到知识库管理页面 */}
            <Route index element={<Navigate to="/knowledgebase" replace />} />

            {/* 面试相关旧路由重定向到知识库 */}
            <Route path="history" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="history/:resumeId" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="upload" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="interview-hub" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="interviews" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="interviews/:sessionId" element={<Navigate to="/knowledgebase" replace />} />
            <Route path={ROUTES.interview.slice(1)} element={<Navigate to="/knowledgebase" replace />} />
            <Route path={ROUTE_PATTERNS.interviewCreate} element={<Navigate to="/knowledgebase" replace />} />
            <Route path={ROUTE_PATTERNS.interviewSession} element={<Navigate to="/knowledgebase" replace />} />
            <Route path="interview/*" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="voice-interview" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="voice-interview/:sessionId/evaluation" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="interview-schedule" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="knowledgebase-interview" element={<Navigate to="/knowledgebase" replace />} />
            <Route path="knowledgebase-interview/*" element={<Navigate to="/knowledgebase" replace />} />

            {/* 知识库管理 */}
            <Route path="knowledgebase" element={<KnowledgeBaseManagePageWrapper />} />

            {/* 知识库上传 */}
            <Route path="knowledgebase/upload" element={<KnowledgeBaseUploadPageWrapper />} />

            {/* 设置 */}
            <Route path="settings" element={<SettingsPage />} />

            {/* 问答助手（知识库聊天） */}
            <Route path="knowledgebase/chat" element={<KnowledgeBaseQueryPageWrapper />} />
          </Route>

        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}

function KnowledgeBaseManagePageWrapper() {
  const navigate = useNavigate();

  const handleUpload = () => {
    navigate(ROUTES.knowledgebaseUpload);
  };

  const handleChat = () => {
    navigate('/knowledgebase/chat');
  };

  return <KnowledgeBaseManagePage onUpload={handleUpload} onChat={handleChat} />;
}

// 知识库问答页面包装器
function KnowledgeBaseQueryPageWrapper() {
  const navigate = useNavigate();
  const location = useLocation();
  const isChatMode = location.pathname === '/knowledgebase/chat';

  const handleBack = () => {
    if (isChatMode) {
      navigate('/knowledgebase');
    } else {
      navigate('/history');
    }
  };

  const handleUpload = () => {
    navigate(ROUTES.knowledgebaseUpload);
  };

  return <KnowledgeBaseQueryPage onBack={handleBack} onUpload={handleUpload} />;
}

// 知识库上传页面包装器
function KnowledgeBaseUploadPageWrapper() {
  const navigate = useNavigate();

  const handleUploadComplete = (_result: UploadKnowledgeBaseResponse) => {
    // 上传完成后返回管理页面
    navigate('/knowledgebase');
  };

  const handleBack = () => {
    navigate('/knowledgebase');
  };

  return <KnowledgeBaseUploadPage onUploadComplete={handleUploadComplete} onBack={handleBack} />;
}

export default App;
