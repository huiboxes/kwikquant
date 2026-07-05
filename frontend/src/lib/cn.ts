import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * className 合并:clsx 拼接 + tailwind-merge 去重(后写的 class 胜出)。
 * shadcn 组件标准 helper。
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
