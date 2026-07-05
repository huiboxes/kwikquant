/**
 * Playwright globalSetup 占位（脚手架阶段）。
 * 业务阶段：调用后端种子 API 准备确定性数据（固定账号 + account + snapshot），
 * 失败即抛错 → spec fail（不静默跳过、不退化 mock）。
 */
export default async function globalSetup(): Promise<void> {
  // TODO(业务阶段): 调用后端 /api/v1/test-support/seed 或类似端点
  // const res = await fetch('http://localhost:8080/actuator/health')
  // if (!res.ok) throw new Error('后端未就绪，E2E 中止')
}
