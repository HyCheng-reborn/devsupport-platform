import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { CheckCircle, AlertCircle } from 'lucide-react';
import { knowledgeBaseApi } from '../api/knowledgebase';
import type { UploadKnowledgeBaseResponse } from '../api/knowledgebase';
import FileUploadCard from '../components/FileUploadCard';

interface KnowledgeBaseUploadPageProps {
  onUploadComplete: (result: UploadKnowledgeBaseResponse) => void;
  onBack: () => void;
}

export default function KnowledgeBaseUploadPage({ onUploadComplete, onBack }: KnowledgeBaseUploadPageProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [duplicateInfo, setDuplicateInfo] = useState<UploadKnowledgeBaseResponse | null>(null);
  const [services, setServices] = useState<string[]>([]);
  const [environments, setEnvironments] = useState<string[]>([]);
  // 阶段 2 资料元数据（页面级输入，随上传提交）
  const [project, setProject] = useState('');
  const [docType, setDocType] = useState('');
  const [source, setSource] = useState('');
  const [versionLabel, setVersionLabel] = useState('');

  useEffect(() => {
    const loadSuggestions = async () => {
      try {
        const [svcList, envList] = await Promise.all([
          knowledgeBaseApi.getAllServices(),
          knowledgeBaseApi.getAllEnvironments(),
        ]);
        setServices(svcList);
        setEnvironments(envList);
      } catch {
        // 加载建议失败不影响上传功能
      }
    };
    loadSuggestions();
  }, []);

  const handleUpload = async (file: File, name?: string, service?: string, environment?: string) => {
    setUploading(true);
    setError('');
    setDuplicateInfo(null);

    try {
      const data = await knowledgeBaseApi.uploadKnowledgeBase(
        file, name, undefined, service, environment,
        project.trim() || undefined, docType.trim() || undefined,
        source.trim() || undefined, versionLabel.trim() || undefined
      );
      if (data.duplicate) {
        setDuplicateInfo(data);
      } else {
        onUploadComplete(data);
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : '上传失败，请重试';
      setError(errorMessage);
    } finally {
      setUploading(false);
    }
  };

  const handleContinue = () => {
    if (duplicateInfo) {
      onUploadComplete(duplicateInfo);
    }
  };

  // 重复文件提示界面
  if (duplicateInfo) {
    const kb = duplicateInfo.knowledgeBase;
    return (
      <motion.div
        className="max-w-3xl mx-auto pt-16"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
      >
        <div className="bg-white dark:bg-slate-800 rounded-2xl p-8 shadow-lg dark:shadow-slate-900/50 text-center">
          <div className="w-16 h-16 mx-auto bg-amber-100 dark:bg-amber-900/30 rounded-full flex items-center justify-center mb-4">
            <AlertCircle className="w-8 h-8 text-amber-500" />
          </div>
          <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">该文件已存在</h2>
          <p className="text-slate-500 dark:text-slate-400 mb-6">
            已有知识库包含相同文件，已自动增加访问计数
          </p>

          <div className="bg-slate-50 dark:bg-slate-700/50 rounded-xl p-4 mb-6 text-left">
            <div className="space-y-2">
              <div className="flex justify-between">
                <span className="text-sm text-slate-500 dark:text-slate-400">知识库名称</span>
                <span className="text-sm font-medium text-slate-900 dark:text-white">{kb.name}</span>
              </div>
              {kb.service && (
                <div className="flex justify-between">
                  <span className="text-sm text-slate-500 dark:text-slate-400">服务标签</span>
                  <span className="px-2 py-0.5 bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 text-sm rounded">
                    {kb.service}
                  </span>
                </div>
              )}
              {kb.environment && (
                <div className="flex justify-between">
                  <span className="text-sm text-slate-500 dark:text-slate-400">环境标签</span>
                  <span className="px-2 py-0.5 bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400 text-sm rounded">
                    {kb.environment}
                  </span>
                </div>
              )}
            </div>
          </div>

          <div className="flex gap-4 justify-center">
            <motion.button
              onClick={onBack}
              className="px-6 py-3 border border-slate-200 dark:border-slate-600 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
            >
              返回管理页
            </motion.button>
            <motion.button
              onClick={handleContinue}
              className="px-6 py-3 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-all flex items-center gap-2"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
            >
              <CheckCircle className="w-4 h-4" />
              继续
            </motion.button>
          </div>
        </div>
      </motion.div>
    );
  }

  return (
    <>
      <div className="max-w-3xl mx-auto pt-8">
        <div className="bg-white dark:bg-slate-800 rounded-2xl p-6 shadow-lg dark:shadow-slate-900/50">
          <h2 className="text-base font-semibold text-slate-800 dark:text-white mb-4">资料元数据（阶段 2）</h2>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">项目（可选）</label>
              <input
                type="text" value={project} onChange={(e) => setProject(e.target.value)}
                maxLength={100} placeholder="如：billing、order-center" disabled={uploading}
                className="w-full px-4 py-3 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">文档类型（可选）</label>
              <input
                type="text" value={docType} onChange={(e) => setDocType(e.target.value)}
                maxLength={50} placeholder="如：runbook、部署手册、错误码" disabled={uploading}
                className="w-full px-4 py-3 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">适用版本（可选）</label>
              <input
                type="text" value={versionLabel} onChange={(e) => setVersionLabel(e.target.value)}
                maxLength={50} placeholder="如：v2.3" disabled={uploading}
                className="w-full px-4 py-3 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">来源（可选）</label>
              <input
                type="text" value={source} onChange={(e) => setSource(e.target.value)}
                maxLength={50} placeholder="如：wiki、git、工单" disabled={uploading}
                className="w-full px-4 py-3 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
              />
            </div>
          </div>
          <p className="text-xs text-slate-400 dark:text-slate-500 mt-3">
            相同 项目+服务+环境+文档类型+名称 的资料再次上传将作为新版本，旧版本自动停用并退出检索。
          </p>
        </div>
      </div>
      <FileUploadCard
        title="上传知识库"
        subtitle="上传文档，AI 将基于知识库内容回答您的问题"
        accept=".pdf,.doc,.docx,.txt,.md"
        formatHint="支持 PDF、DOCX、DOC、TXT、MD"
        maxSizeHint="最大 50MB"
        uploading={uploading}
        uploadButtonText="开始上传"
        selectButtonText="选择文件"
        showNameInput={true}
        nameLabel="知识库名称（可选）"
        namePlaceholder="留空则使用文件名"
        showLabelInputs={true}
        serviceSuggestions={services}
        environmentSuggestions={environments}
        error={error}
        onUpload={handleUpload}
        onBack={onBack}
      />
    </>
  );
}
