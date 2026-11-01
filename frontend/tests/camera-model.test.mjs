import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const src = ts.transpileModule(readFileSync('src/features/integrity/camera/cameraModel.ts', 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { CameraRules, THRESHOLDS, distance, headPose, recorderMime } = await import('data:text/javascript,' + encodeURIComponent(src))

test('a single frame never reports; a sustained condition reports once per cooldown', () => {
  const r = new CameraRules()
  assert.deepEqual(r.observe({ at: 0, faces: 0 }), [])
  assert.deepEqual(r.observe({ at: 2000, faces: 0 }), [])
  const hit = r.observe({ at: 3000, faces: 0 })
  assert.equal(hit.length, 1); assert.equal(hit[0].type, 'NO_FACE'); assert.equal(hit[0].detail.seconds, 3)
  assert.deepEqual(r.observe({ at: 9000, faces: 0 }), [], 'cooldown')
  assert.equal(r.observe({ at: 3000 + THRESHOLDS.cooldown, faces: 0 }).length, 1)
})

test('a condition that stops resets its timer', () => {
  const r = new CameraRules()
  r.observe({ at: 0, faces: 0 }); r.observe({ at: 2500, faces: 1, distance: 0.3 })
  assert.deepEqual(r.observe({ at: 3500, faces: 0 }), [])
  assert.equal(r.observe({ at: 6500, faces: 0 })[0].type, 'NO_FACE')
})

test('identity, other people and looking away use their own thresholds', () => {
  const r = new CameraRules()
  assert.deepEqual(r.conditions({ at: 0, faces: 1, distance: 0.4, yaw: 5, pitch: 3 }), [])
  assert.deepEqual(r.conditions({ at: 0, faces: 2 }).map(c => c.type), ['MULTIPLE_FACES'])
  const mismatch = r.conditions({ at: 0, faces: 1, distance: 0.72 })
  assert.equal(mismatch[0].type, 'FACE_MISMATCH'); assert.equal(mismatch[0].detail.threshold, THRESHOLDS.matchDistance)
  assert.deepEqual(r.conditions({ at: 0, faces: 1, distance: 0.3, yaw: -40 }).map(c => c.type), ['LOOKING_AWAY'])
  assert.deepEqual(r.conditions({ at: 0, faces: 1, pitch: 35 }).map(c => c.type), ['LOOKING_AWAY'])
  assert.equal(r.pending({ at: 0, faces: 0 }), 'NO_FACE')
  assert.equal(r.pending({ at: 0, faces: 1, distance: 0.2 }), null)
  // Multiple faces is reported faster than a mismatch.
  r.observe({ at: 0, faces: 2 })
  assert.equal(r.observe({ at: 1500, faces: 2 })[0].type, 'MULTIPLE_FACES')
})

test('descriptor distance and head pose', () => {
  assert.equal(distance([0, 0, 0], [3, 4, 0]), 5)
  const pts = Array.from({ length: 68 }, () => ({ x: 0, y: 0 }))
  const set = (i, x, y) => { pts[i] = { x, y } }
  set(36, 40, 40); set(45, 80, 40); set(8, 60, 100)
  set(30, 60, 65); const level = headPose(pts)
  set(30, 75, 65); const turned = headPose(pts)
  assert.ok(Math.abs(level.yaw) < 1)
  assert.ok(turned.yaw > 30, `turned ${turned.yaw}`)
  assert.equal(headPose(pts.slice(0, 10)), null)
})

test('recording format preference: small WebM first, MP4 on Safari, none when unsupported', () => {
  assert.equal(recorderMime(t => t.startsWith('video/webm')), 'video/webm;codecs=vp9')
  assert.equal(recorderMime(t => t === 'video/mp4'), 'video/mp4')
  assert.equal(recorderMime(() => false), null)
})
