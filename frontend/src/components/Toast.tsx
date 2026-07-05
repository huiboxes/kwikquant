/* eslint-disable react-refresh/only-export-components -- Toaster(组件) + toast(sonner 函数) 混合导出,react-refresh 不适用 */
import { toast } from 'sonner'
import { Toaster } from '@/components/ui/sonner'

/**
 * Toast — 全局通知封装(spec §5 共享组件 #4,F12.1)。
 *
 * 基于 sonner(F12.1: success/error/warning/info,自动消失 3s/5s/手动关闭 error)。
 * 接 react-query mutation onError:toast.error(message)。
 *
 * 用法:
 *   <AppShell>{children} <Toaster /></AppShell>  // 挂载一次
 *   toast.success('策略已创建')
 *   toast.error('提交失败:余额不足')
 */
export { Toaster, toast }
