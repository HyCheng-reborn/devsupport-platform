import {Link, Outlet, useLocation} from 'react-router-dom';
import {motion} from 'framer-motion';
import {ChevronRight, FileText, MessageSquare, Moon, Settings, Sun, BarChart3, FolderOpen,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {ROUTES} from '../constants/routes';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();

  // 四个主导航入口
  const mainNavItems: NavItem[] = [
    { id: 'docs', path: ROUTES.docsCenter, label: '文档中心', icon: FileText, description: '管理知识文档' },
    { id: 'chat', path: ROUTES.chatSessions, label: '排查会话', icon: MessageSquare, description: 'RAG 排查对话' },
    { id: 'cases', path: ROUTES.cases, label: '案例库', icon: FolderOpen, description: '故障案例检索' },
    { id: 'eval', path: ROUTES.evalResults, label: '评测结果', icon: BarChart3, description: 'RAG 检索评测' },
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path === ROUTES.docsCenter) {
      return currentPath === '/docs' || currentPath.startsWith('/docs/');
    }
    if (path === ROUTES.chatSessions) {
      return currentPath === '/chat' || currentPath.startsWith('/chat/');
    }
    return currentPath === path || currentPath.startsWith(path + '/');
  };

  return (
    <div className="flex min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
      {/* 左侧边栏 */}
      <aside className="w-64 bg-white dark:bg-slate-900 border-r border-slate-100 dark:border-slate-700 fixed h-screen left-0 top-0 z-50 flex flex-col">
        {/* Logo */}
        <div className="p-6 border-b border-slate-100 dark:border-slate-700">
          <Link to={ROUTES.docsCenter} className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-primary-500 to-primary-600 rounded-xl flex items-center justify-center text-white shadow-lg shadow-primary-500/30">
              <FileText className="w-5 h-5" />
            </div>
            <div>
              <span className="text-lg font-bold text-slate-800 dark:text-white tracking-tight block">DevSupport</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">研发支持平台</span>
            </div>
          </Link>
        </div>

        {/* 主题切换按钮 */}
        <div className="px-4 pb-2 pt-4">
          <button
            onClick={toggleTheme}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="w-4 h-4" />
                <span className="text-sm font-medium">浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="w-4 h-4" />
                <span className="text-sm font-medium">深色模式</span>
              </>
            )}
          </button>
        </div>

        {/* 主导航菜单 */}
        <nav className="flex-1 p-4 overflow-y-auto">
          <div className="space-y-1">
            {mainNavItems.map((item) => {
              const active = isActive(item.path);

              return (
                <Link
                  key={item.id}
                  to={item.path}
                  className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all duration-200
                    ${active
                      ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400'
                      : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white'
                    }`}
                >
                  <div className={`w-9 h-9 rounded-lg flex items-center justify-center transition-colors
                    ${active
                      ? 'bg-primary-100 dark:bg-primary-900/50 text-primary-600 dark:text-primary-400'
                      : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 group-hover:bg-slate-200 dark:group-hover:bg-slate-700 group-hover:text-slate-700 dark:group-hover:text-white'
                    }`}
                  >
                    <item.icon className="w-5 h-5" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>
                      {item.label}
                    </span>
                    {item.description && (
                      <span className="text-xs text-slate-400 dark:text-slate-500 truncate block">
                        {item.description}
                      </span>
                    )}
                  </div>
                  {active && <ChevronRight className="w-4 h-4 text-primary-400" />}
                </Link>
              );
            })}
          </div>

          {/* 分隔线 */}
          <div className="my-6 border-t border-slate-100 dark:border-slate-700" />

          {/* 设置入口 */}
          <div className="space-y-1">
            <Link
              to={ROUTES.settings}
              className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all duration-200
                ${currentPath === '/settings'
                  ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400'
                  : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white'
                }`}
            >
              <div className={`w-9 h-9 rounded-lg flex items-center justify-center transition-colors
                ${currentPath === '/settings'
                  ? 'bg-primary-100 dark:bg-primary-900/50 text-primary-600 dark:text-primary-400'
                  : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 group-hover:bg-slate-200 dark:group-hover:bg-slate-700 group-hover:text-slate-700 dark:group-hover:text-white'
                }`}
              >
                <Settings className="w-5 h-5" />
              </div>
              <div className="flex-1 min-w-0">
                <span className={`text-sm block ${currentPath === '/settings' ? 'font-semibold' : 'font-medium'}`}>
                  设置
                </span>
                <span className="text-xs text-slate-400 dark:text-slate-500 truncate block">
                  管理模型和语音服务
                </span>
              </div>
              {currentPath === '/settings' && <ChevronRight className="w-4 h-4 text-primary-400" />}
            </Link>
          </div>
        </nav>

        {/* 底部信息 */}
        <div className="p-4 border-t border-slate-100 dark:border-slate-700">
          <div className="px-3 py-2 bg-gradient-to-r from-primary-50 to-indigo-50 dark:from-primary-900/30 dark:to-slate-800 rounded-xl">
            <p className="text-xs text-primary-600 dark:text-primary-400 font-medium">DevSupport v1.0</p>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">研发支持平台</p>
          </div>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className="flex-1 ml-64 p-10 min-h-screen overflow-y-auto">
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -20 }}
          transition={{ duration: 0.3 }}
        >
          <Outlet />
        </motion.div>
      </main>
    </div>
  );
}
