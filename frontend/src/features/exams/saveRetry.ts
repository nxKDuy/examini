/** Answer saves overwrite the stored answer, so transient failures are safe to retry automatically. */
export const SAVE_RETRIES = 3

/** Network failure (no HTTP status), a server/gateway error or a timeout: worth retrying. Never 4xx decisions. */
export function isTransientSaveError(status?: number): boolean {
  return status === undefined || status === 0 || status === 408 || status === 429 || status >= 500
}

/** 0.8 s, 1.6 s, 3.2 s … with a little jitter so a whole class does not retry in lock-step. */
export function saveRetryDelay(attempt: number, random: () => number = Math.random): number {
  return Math.round(800 * 2 ** attempt * (0.85 + 0.3 * random()))
}
