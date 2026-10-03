import { useCallback, useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Filter,
  Globe,
  Server,
  Folder,
  Tag,
  Loader2,
  ChevronDown,
  X,
} from 'lucide-react';
import { knowledgeBaseApi, type ContextKbItem } from '../api/knowledgebase';

export interface ContextSelectorProps {
  onScopeChange?: (kbIds: number[], service?: string, environment?: string) => void;
}

export default function ContextSelector({ onScopeChange }: ContextSelectorProps) {
  const [services, setServices] = useState<string[]>([]);
  const [environments, setEnvironments] = useState<string[]>([]);
  const [selectedService, setSelectedService] = useState<string>('');
  const [selectedEnvironment, setSelectedEnvironment] = useState<string>('');
  const [resolvedItems, setResolvedItems] = useState<ContextKbItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [showDropdown, setShowDropdown] = useState<'service' | 'environment' | null>(null);

  // 加载 services / environments 选项
  useEffect(() => {
    (async () => {
      try {
        const [svcList, envList] = await Promise.all([
          knowledgeBaseApi.getAllServices(),
          knowledgeBaseApi.getAllEnvironments(),
        ]);
        setServices(svcList);
        setEnvironments(envList);
      } catch (err) {
        console.error('加载上下文选项失败', err);
      }
    })();
  }, []);

  // 当选择变化时，调用 resolveContext
  const resolve = useCallback(async (service: string, environment: string) => {
    if (!service && !environment) {
      setResolvedItems([]);
      onScopeChange?.([], undefined, undefined);
      return;
    }
    setLoading(true);
    try {
      const items = await knowledgeBaseApi.resolveContext(
        service || undefined,
        environment || undefined
      );
      setResolvedItems(items);
      onScopeChange?.(items.map((i) => i.id), service || undefined, environment || undefined);
    } catch (err) {
      console.error('解析上下文失败', err);
      setResolvedItems([]);
    } finally {
      setLoading(false);
    }
  }, [onScopeChange]);

  const handleServiceChange = (value: string) => {
    setSelectedService(value);
    setShowDropdown(null);
    resolve(value, selectedEnvironment);
  };

  const handleEnvironmentChange = (value: string) => {
    setSelectedEnvironment(value);
    setShowDropdown(null);
    resolve(selectedService, value);
  };

  const handleClear = () => {
    setSelectedService('');
    setSelectedEnvironment('');
    setResolvedItems([]);
    onScopeChange?.([], undefined, undefined);
  };

  const hasContext = selectedService || selectedEnvironment;
  const maxVisibleNames = 5;
  const visibleNames = resolvedItems.slice(0, maxVisibleNames);
  const remainingCount = resolvedItems.length - maxVisibleNames;

  return (
    <div className="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-100 dark:border-slate-700 mb-6">
      {/* 标题行 */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
          <Filter className="w-4 h-4 text-primary-500" />
          上下文范围
        </div>
        {hasContext && (
          <button
            onClick={handleClear}
            className="flex items-center gap-1 text-xs text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
          >
            <X className="w-3 h-3" />
            清除
          </button>
        )}
      </div>

      {/* 选择器行 */}
      <div className="flex flex-wrap items-center gap-3">
        {/* 项目/服务 下拉 */}
        <div className="relative">
          <button
            onClick={() => setShowDropdown(showDropdown === 'service' ? null : 'service')}
            className={`flex items-center gap-2 pl-3 pr-8 py-2 border rounded-lg text-sm transition-all appearance-none cursor-pointer ${
              selectedService
                ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/20 text-primary-700 dark:text-primary-300'
                : 'border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300'
            } focus:outline-none focus:ring-2 focus:ring-primary-500`}
          >
            <Server className="w-3.5 h-3.5" />
            {selectedService || '项目/服务'}
            <ChevronDown className="absolute right-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-400 pointer-events-none" />
          </button>
          <AnimatePresence>
            {showDropdown === 'service' && (
              <motion.div
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                transition={{ duration: 0.15 }}
                className="absolute z-20 top-full mt-1 left-0 min-w-[180px] bg-white dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-lg shadow-lg overflow-hidden"
              >
                <button
                  onClick={() => handleServiceChange('')}
                  className="w-full text-left px-3 py-2 text-sm hover:bg-slate-50 dark:hover:bg-slate-600 text-slate-600 dark:text-slate-300 transition-colors"
                >
                  全部服务
                </button>
                {services.map((svc) => (
                  <button
                    key={svc}
                    onClick={() => handleServiceChange(svc)}
                    className={`w-full text-left px-3 py-2 text-sm hover:bg-slate-50 dark:hover:bg-slate-600 transition-colors ${
                      selectedService === svc
                        ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 font-medium'
                        : 'text-slate-700 dark:text-slate-200'
                    }`}
                  >
                    {svc}
                  </button>
                ))}
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* 环境 下拉 */}
        <div className="relative">
          <button
            onClick={() => setShowDropdown(showDropdown === 'environment' ? null : 'environment')}
            className={`flex items-center gap-2 pl-3 pr-8 py-2 border rounded-lg text-sm transition-all appearance-none cursor-pointer ${
              selectedEnvironment
                ? 'border-emerald-400 bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300'
                : 'border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300'
            } focus:outline-none focus:ring-2 focus:ring-primary-500`}
          >
            <Globe className="w-3.5 h-3.5" />
            {selectedEnvironment || '环境'}
            <ChevronDown className="absolute right-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-400 pointer-events-none" />
          </button>
          <AnimatePresence>
            {showDropdown === 'environment' && (
              <motion.div
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                transition={{ duration: 0.15 }}
                className="absolute z-20 top-full mt-1 left-0 min-w-[160px] bg-white dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-lg shadow-lg overflow-hidden"
              >
                <button
                  onClick={() => handleEnvironmentChange('')}
                  className="w-full text-left px-3 py-2 text-sm hover:bg-slate-50 dark:hover:bg-slate-600 text-slate-600 dark:text-slate-300 transition-colors"
                >
                  全部环境
                </button>
                {environments.map((env) => (
                  <button
                    key={env}
                    onClick={() => handleEnvironmentChange(env)}
                    className={`w-full text-left px-3 py-2 text-sm hover:bg-slate-50 dark:hover:bg-slate-600 transition-colors ${
                      selectedEnvironment === env
                        ? 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400 font-medium'
                        : 'text-slate-700 dark:text-slate-200'
                    }`}
                  >
                    {env}
                  </button>
                ))}
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* project / version — 禁用态，阶段 2 前置 */}
        <div className="relative group disabled:cursor-not-allowed">
          <button
            disabled
            className="flex items-center gap-2 pl-3 pr-3 py-2 border border-slate-200 dark:border-slate-700 rounded-lg text-sm text-slate-300 dark:text-slate-600 bg-slate-50 dark:bg-slate-800 cursor-not-allowed"
          >
            <Folder className="w-3.5 h-3.5" />
            项目
          </button>
          <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-2 py-1 bg-slate-800 dark:bg-slate-600 text-white text-xs rounded whitespace-nowrap opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity">
            阶段 2 前置工作
          </div>
        </div>
        <div className="relative group disabled:cursor-not-allowed">
          <button
            disabled
            className="flex items-center gap-2 pl-3 pr-3 py-2 border border-slate-200 dark:border-slate-700 rounded-lg text-sm text-slate-300 dark:text-slate-600 bg-slate-50 dark:bg-slate-800 cursor-not-allowed"
          >
            <Tag className="w-3.5 h-3.5" />
            版本
          </button>
          <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-2 py-1 bg-slate-800 dark:bg-slate-600 text-white text-xs rounded whitespace-nowrap opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity">
            阶段 2 前置工作
          </div>
        </div>
      </div>

      {/* 范围展示 */}
      <div className="mt-3 pt-3 border-t border-slate-100 dark:border-slate-700">
        {loading ? (
          <div className="flex items-center gap-2 text-sm text-slate-400">
            <Loader2 className="w-3.5 h-3.5 animate-spin" />
            解析中...
          </div>
        ) : hasContext ? (
          <div>
            <p className="text-sm text-slate-600 dark:text-slate-300">
              当前检索范围：
              <span className="font-semibold text-primary-600 dark:text-primary-400">
                {resolvedItems.length} 个文档
              </span>
            </p>
            {resolvedItems.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mt-2">
                {visibleNames.map((item) => (
                  <span
                    key={item.id}
                    className="px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 text-xs rounded truncate max-w-[160px]"
                    title={item.name}
                  >
                    {item.name}
                  </span>
                ))}
                {remainingCount > 0 && (
                  <span className="px-2 py-0.5 text-slate-400 dark:text-slate-500 text-xs">
                    +{remainingCount} 更多
                  </span>
                )}
              </div>
            )}
          </div>
        ) : (
          <p className="text-sm text-slate-400 dark:text-slate-500">
            全范围检索（未选择过滤条件）
          </p>
        )}
      </div>
    </div>
  );
}
