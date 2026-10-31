// Face analysis in the browser (@vladmandic/face-api, TensorFlow.js). Loaded lazily on the exam page only; the
// models (~6.7 MB: tiny detector, tiny 68-point landmarks, recognition) are served from /models/face.
import { headPose } from './cameraModel'

type FaceApi = typeof import('@vladmandic/face-api')
let loading: Promise<FaceApi> | null = null

export const MODEL_URL = '/models/face'

export function loadFaceEngine(): Promise<FaceApi> {
  loading ??= (async () => {
    const faceapi = await import('@vladmandic/face-api')
    // The bundled TF.js picks WebGL when available, else WASM/CPU; ready() is untyped in the bundle's d.ts.
    await (faceapi.tf as unknown as { ready: () => Promise<void> }).ready()
    await Promise.all([
      faceapi.nets.tinyFaceDetector.loadFromUri(MODEL_URL),
      faceapi.nets.faceLandmark68TinyNet.loadFromUri(MODEL_URL),
      faceapi.nets.faceRecognitionNet.loadFromUri(MODEL_URL),
    ])
    return faceapi
  })().catch(e => { loading = null; throw e })
  return loading
}

export interface FrameAnalysis { faces: number; descriptor: Float32Array | null; yaw: number | null; pitch: number | null }

/** A weak detection is enough to find the student's own face (tilted heads, dim rooms)… */
export const PRIMARY_SCORE = 0.35
/** …but another person counts only when clearly detected, so shadows and posters do not raise "several people". */
export const EXTRA_SCORE = 0.55

/** Counts faces and, for the largest one, returns its descriptor and head pose. */
export async function analyse(faceapi: FaceApi, input: HTMLVideoElement | HTMLCanvasElement): Promise<FrameAnalysis> {
  const options = new faceapi.TinyFaceDetectorOptions({ inputSize: 224, scoreThreshold: PRIMARY_SCORE })
  const results = await faceapi.detectAllFaces(input, options).withFaceLandmarks(true).withFaceDescriptors()
  if (!results.length) return { faces: 0, descriptor: null, yaw: null, pitch: null }
  const main = results.reduce((a, b) => (a.detection.box.area >= b.detection.box.area ? a : b))
  const others = results.filter(r => r !== main && r.detection.score >= EXTRA_SCORE).length
  const pose = headPose(main.landmarks.positions)
  return { faces: 1 + others, descriptor: main.descriptor, yaw: pose?.yaw ?? null, pitch: pose?.pitch ?? null }
}

/** JPEG snapshot of the current frame, small enough for evidence (≤ ~40 KB at 320 px). */
export function snapshot(video: HTMLVideoElement, width = 320, quality = 0.7): Promise<Blob | null> {
  const ratio = video.videoWidth ? video.videoHeight / video.videoWidth : 0.75
  const canvas = document.createElement('canvas')
  canvas.width = width; canvas.height = Math.round(width * ratio)
  canvas.getContext('2d')?.drawImage(video, 0, 0, canvas.width, canvas.height)
  return new Promise(resolve => canvas.toBlob(b => resolve(b), 'image/jpeg', quality))
}
