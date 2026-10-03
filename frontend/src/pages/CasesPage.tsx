import { motion } from 'framer-motion';
import { Construction } from 'lucide-react';

export default function CasesPage() {
  return (
    <div className="flex items-center justify-center min-h-[60vh]">
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5 }}
        className="text-center max-w-md"
      >
        <div className="w-20 h-20 mx-auto mb-6 rounded-2xl bg-gradient-to-br from-amber-100 to-orange-100 dark:from-amber-900/30 dark:to-orange-900/20 flex items-center justify-center">
          <Construction className="w-10 h-10 text-amber-500 dark:text-amber-400" />
        </div>
        <h1 className="text-2xl font-display font-bold text-slate-900 dark:text-white mb-3">
          案例库 — 待建设
        </h1>
        <p className="text-slate-500 dark:text-slate-400 leading-relaxed">
          故障案例库将在阶段 2 中实现，包括脱敏故障记录的收集、分类和检索。
        </p>
      </motion.div>
    </div>
  );
}
