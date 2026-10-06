/**
 * Camera + QR detection. BarcodeDetector (Shape Detection API) when the browser
 * has it — hardware-accelerated on Android Chrome — and jsQR as an everywhere-else
 * fallback (Firefox, Safari, desktops). Mirrors the Android scanner: keep-only-latest
 * analysis, ~8 fps is plenty, and the caller gates delivery to exactly once.
 */
import jsQR from 'jsqr'

export interface CameraSession {
  torchSupported: boolean
  setTorch(on: boolean): Promise<void>
  stop(): void
}

export async function openCamera(video: HTMLVideoElement, facing: 'environment' | 'user' = 'environment'): Promise<CameraSession> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: false,
    video: { facingMode: { ideal: facing }, width: { ideal: 1280 }, height: { ideal: 720 } }
  })
  video.srcObject = stream
  await video.play()
  const track = stream.getVideoTracks()[0]
  if (!track) {
    for (const t of stream.getTracks()) t.stop()
    throw new Error('camera stream has no video track')
  }
  const caps = typeof track.getCapabilities === 'function' ? track.getCapabilities() : {}
  const torchSupported = 'torch' in caps && caps.torch === true
  return {
    torchSupported,
    async setTorch(on: boolean) {
      // torch is a real (ImageCapture) constraint the DOM typings don't know yet.
      await track.applyConstraints({ advanced: [{ torch: on }] } as unknown as MediaTrackConstraints)
    },
    stop() {
      for (const t of stream.getTracks()) t.stop()
      video.srcObject = null
    }
  }
}

export interface Detection {
  cancel(): void
}

export function startDetection(video: HTMLVideoElement, onText: (text: string) => void): Detection {
  let cancelled = false
  let lastAttempt = 0
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d', { willReadFrequently: true })
  const Detector = (window as unknown as { BarcodeDetector?: new (opts: { formats: string[] }) => { detect(source: CanvasImageSource): Promise<Array<{ rawValue?: string }>> } }).BarcodeDetector
  const native = Detector ? new Detector({ formats: ['qr_code'] }) : null

  const tick = async (now: number) => {
    if (cancelled) return
    if (now - lastAttempt >= 120 && video.readyState >= 2 && !video.paused) {
      lastAttempt = now
      try {
        if (native) {
          const codes = await native.detect(video)
          const text = codes.find(c => c.rawValue)?.rawValue
          if (text) onText(text)
        } else if (context) {
          const w = video.videoWidth
          const h = video.videoHeight
          if (w && h) {
            if (canvas.width !== w || canvas.height !== h) {
              canvas.width = w
              canvas.height = h
            }
            context.drawImage(video, 0, 0)
            const { data } = context.getImageData(0, 0, w, h)
            const found = jsQR(data, w, h, { inversionAttempts: 'attemptBoth' })
            if (found?.data) onText(found.data)
          }
        }
      } catch {
        // A dropped frame must not kill the loop; the next frame tries again.
      }
    }
    requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
  return {
    cancel() {
      cancelled = true
    }
  }
}

/** Keeps the screen awake while a scan is in flight; null where Wake Lock is unavailable. */
export async function acquireWakeLock(): Promise<WakeLockSentinel | null> {
  try {
    return await navigator.wakeLock.request('screen')
  } catch {
    return null
  }
}
