export interface OrderIntent<T> {
  signature: string
  request: T
}

/** 相同参数的超时/失败重试复用 clientOrderId；参数变化生成新的订单意图。 */
export function prepareOrderIntent<T extends { clientOrderId: string; expireAt: string }>(
  current: OrderIntent<T> | null,
  request: T,
): OrderIntent<T> {
  const signature = JSON.stringify({ ...request, clientOrderId: '' })
  if (current?.signature === signature) return current

  return {
    signature,
    request: { ...request, clientOrderId: crypto.randomUUID() },
  }
}
