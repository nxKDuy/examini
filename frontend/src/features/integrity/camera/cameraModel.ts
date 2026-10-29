// Pure rules of camera proctoring, testable without a camera. The browser measures, these rules decide when a
// measurement is a violation worth reporting: one frame never is, a sustained condition is.

export type CameraEventType = 'REFERENCE' | 'NO_FACE' | 'MULTIPLE_FACES' | 'FACE_MISMATCH' | 'LOOKING_AWAY' | 'CAMERA_OFF' | 'CAMERA_ON'
export type Violation = 'NO_FACE' | 'MULTIPLE_FACES' | 'FACE_MISMATCH' | 'LOOKING_AWAY'

/** One analysed frame. distance: face descriptor distance to the enrollment photo (lower = same person). */
export interface Observation { at: number; faces: number; distance?: number | null; yaw?: number | null; pitch?: number | null }

export const THRESHOLDS = {
  /** Euclidean distance of 128-d descriptors; ~0.6 is the usual same-person boundary, 0.55 is a little stricter. */
  matchDistance: 0.55,
  /** Degrees of head turn (left/right) and tilt (down/up) treated as not looking at the screen. */
  yaw: 32, pitch: 28,
  /** How long a condition must last before it is reported (ms). */
  sustain: { NO_FACE: 3000, MULTIPLE_FACES: 1500, FACE_MISMATCH: 4500, LOOKING_AWAY: 5000 } as Record<Violation, number>,
  /** Minimum gap between two reports of the same violation (ms). */
  cooldown: 20_000,
}

export interface Detected { type: Violation; seconds: number; detail: Record<string, number> }

/** Tracks how long each condition has held and emits a violation once it is sustained (then waits for the cooldown). */
export class CameraRules {
  private since: Partial<Record<Violation, number>> = {}
  private lastReport: Partial<Record<Violation, number>> = {}
  private readonly t: typeof THRESHOLDS
  constructor(t = THRESHOLDS) { this.t = t }

  /** Conditions present in one frame, most serious first. */
  conditions(o: Observation): { type: Violation; detail: Record<string, number> }[] {
    const out: { type: Violation; detail: Record<string, number> }[] = []
    if (o.faces === 0) out.push({ type: 'NO_FACE', detail: { faces: 0 } })
    if (o.faces > 1) out.push({ type: 'MULTIPLE_FACES', detail: { faces: o.faces } })
    if (o.faces === 1 && o.distance != null && o.distance > this.t.matchDistance)
      out.push({ type: 'FACE_MISMATCH', detail: { distance: round(o.distance), threshold: this.t.matchDistance } })
    if (o.faces === 1 && ((o.yaw != null && Math.abs(o.yaw) > this.t.yaw) || (o.pitch != null && Math.abs(o.pitch) > this.t.pitch)))
      out.push({ type: 'LOOKING_AWAY', detail: { yaw: round(o.yaw ?? 0), pitch: round(o.pitch ?? 0) } })
    return out
  }

  /** Feed one observation; returns the violations to report now (possibly none). */
  observe(o: Observation): Detected[] {
    const present = this.conditions(o)
    const kinds = new Set(present.map(p => p.type))
    for (const k of Object.keys(this.since) as Violation[]) if (!kinds.has(k)) delete this.since[k]
    const out: Detected[] = []
    for (const p of present) {
      const start = this.since[p.type] ??= o.at
      const held = o.at - start
      const last = this.lastReport[p.type]
      if (held >= this.t.sustain[p.type] && (last === undefined || o.at - last >= this.t.cooldown)) {
        this.lastReport[p.type] = o.at
        out.push({ type: p.type, seconds: Math.round(held / 100) / 10, detail: { ...p.detail, seconds: Math.round(held / 100) / 10 } })
      }
    }
    return out
  }

  /** Current live warning for the student (shown before anything is reported). */
  pending(o: Observation): Violation | null { return this.conditions(o)[0]?.type ?? null }
}

const round = (n: number) => Math.round(n * 100) / 100

/** Euclidean distance of two descriptors (face-api uses 128 floats). */
export function distance(a: ArrayLike<number>, b: ArrayLike<number>) {
  let s = 0
  for (let i = 0; i < Math.min(a.length, b.length); i++) { const d = a[i] - b[i]; s += d * d }
  return Math.sqrt(s)
}

type Point = { x: number; y: number }
/**
 * Approximate head pose from 68 landmarks (iBUG layout): yaw from the nose tip's horizontal offset between the
 * outer eye corners, pitch from its vertical offset between the eye line and the chin. Degrees, 0 = facing the screen.
 */
export function headPose(p: Point[]): { yaw: number; pitch: number } | null {
  if (p.length < 68) return null
  const leftEye = p[36], rightEye = p[45], nose = p[30], chin = p[8]
  const eyeMid = { x: (leftEye.x + rightEye.x) / 2, y: (leftEye.y + rightEye.y) / 2 }
  const eyeSpan = Math.hypot(rightEye.x - leftEye.x, rightEye.y - leftEye.y)
  const faceHeight = Math.hypot(chin.x - eyeMid.x, chin.y - eyeMid.y)
  if (eyeSpan < 1 || faceHeight < 1) return null
  const yawRatio = (nose.x - eyeMid.x) / (eyeSpan / 2) // -1..1 when turning fully
  const pitchRatio = (nose.y - eyeMid.y) / faceHeight - 0.42 // nose tip sits ~42% down from eyes to chin when level
  const clamp = (v: number) => Math.max(-1, Math.min(1, v))
  return { yaw: Math.asin(clamp(yawRatio)) * 180 / Math.PI, pitch: Math.asin(clamp(pitchRatio * 2.2)) * 180 / Math.PI }
}

/** Best supported recording format: small WebM (VP9/VP8) where possible, MP4 on Safari. */
export function recorderMime(isSupported: (t: string) => boolean): string | null {
  for (const t of ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm', 'video/mp4;codecs=avc1', 'video/mp4'])
    if (isSupported(t)) return t
  return null
}

/** ~250 kbit/s at 320×240 / 15 fps: about 1.9 MB per minute, ~110 MB for a 60-minute exam. */
export const RECORDING = { width: 320, height: 240, frameRate: 15, videoBitsPerSecond: 250_000, chunkMs: 10_000 }
