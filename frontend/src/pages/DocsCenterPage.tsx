import { useNavigate } from 'react-router-dom';
import KnowledgeBaseManagePage from './KnowledgeBaseManagePage';
import { ROUTES } from '../constants/routes';

/**
 * 文档中心页面 — 复用 KnowledgeBaseManagePage 的完整功能。
 * 导航入口为"文档中心"，内部沿用知识库管理的列表、筛选、编辑能力。
 */
export default function DocsCenterPage() {
  const navigate = useNavigate();

  const handleUpload = () => {
    navigate(ROUTES.docsUpload);
  };

  const handleChat = () => {
    navigate(ROUTES.chatSessions);
  };

  return <KnowledgeBaseManagePage onUpload={handleUpload} onChat={handleChat} />;
}
