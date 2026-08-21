/**
 * docs — 仓库文档外链单一真相源。
 *
 * 本地阶段指向 GitHub 仓库 markdown；公网分发后改 DOC_BASE 即可全局切换(落地页/注册页等共用)。
 */
export const DOC_BASE = 'https://github.com/huiboxes/kwikquant/blob/main'

export const docUrl = (path: string) => `${DOC_BASE}/${path}`
