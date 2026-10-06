/**
 * OpusVoice QR Console — the web twin of the Android app's QR system.
 * Scan (camera or paste) -> typed payload -> details / copy / share / re-encode;
 * Generate any text into a QR; History persists on this device.
 */
import './styles.css'
import { parseQrPayload, type QrPayload } from './payload'
import { acquireWakeLock, openCamera, startDetection, type CameraSession, type Detection } from './scanner'
import { clearQrCanvas, renderQrCanvas } from './generator'

const HISTORY_KEY = 'opusvoice.qr.history'
const HISTORY_LIMIT = 20
const TABS = ['scan', 'generate', 'history'] as const
type TabName = (typeof TABS)[number]

interface HistoryEntry {
  when: number
  raw: string
  kind: QrPayload['kind']
}

const $ = <T extends HTMLElement>(selector: string) => document.querySelector(selector) as T

// ---- toast -----------------------------------------------------------------

let toastTimer = 0

function toast(message: string): void {
  const el = $('#toast')
  el.textContent = message
  el.classList.add('shown')
  clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => el.classList.remove('shown'), 2400)
}

async function copyText(text: string): Promise<void> {
  await navigator.clipboard.writeText(text)
  toast('Copied to clipboard')
}

// ---- tabs (with same-document View Transitions where available) -------------

function switchTab(name: TabName): void {
  const apply = () => {
    for (const t of TABS) {
      $(`#tab-${t}`).hidden = t !== name
      $(`[data-tab="${t}"]`).setAttribute('aria-selected', String(t === name))
      $(`[data-tab="${t}"]`).classList.toggle('active', t === name)
    }
  }
  const doc = document as Document & { startViewTransition?(cb: () => void): void }
  if (typeof doc.startViewTransition === 'function') doc.startViewTransition(apply)
  else apply()
}

for (const btn of document.querySelectorAll<HTMLButtonElement>('[data-tab]')) {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab as TabName))
}

// ---- payload rendering ------------------------------------------------------

interface PayloadDescription {
  badge: string
  className: 'ticket' | 'udp' | 'text'
  title: string
  rows: Array<[string, string]>
}

function describePayload(payload: QrPayload): PayloadDescription {
  switch (payload.kind) {
    case 'pinhole-ticket': {
      const p = payload.parsed
      const rows: Array<[string, string]> = [
        ['Peer ID', `0x${p.peerId.toString(16).toUpperCase().padStart(16, '0')}`],
        ['NAT hint', p.natHint],
        ['String', `v${p.version}, ${p.candidates.length} candidate${p.candidates.length === 1 ? '' : 's'}`]
      ]
      for (const c of p.candidates.slice(0, 6)) rows.push([c.kind, `${c.host}:${c.port}`])
      if (p.candidates.length > 6) rows.push(['…', `+${p.candidates.length - 6} more candidates`])
      rows.push(['Static key', p.staticKey ? 'pinned ✓' : 'none'])
      if (p.endpointKey) rows.push(['Endpoint key', 'pinned ✓'])
      return { badge: 'PINHOLE TICKET', className: 'ticket', title: 'Connection string decoded', rows }
    }
    case 'udp-endpoint':
      return {
        badge: 'UDP TARGET',
        className: 'udp',
        title: 'RTP target decoded',
        rows: [
          ['Host', payload.host],
          ['Port', String(payload.port)]
        ]
      }
    default:
      return {
        badge: 'TEXT',
        className: 'text',
        title: 'No known format — raw content',
        rows: [['Content', payload.text.slice(0, 400)]]
      }
  }
}

function payloadRawText(payload: QrPayload): string {
  switch (payload.kind) {
    case 'pinhole-ticket':
      return payload.ticket
    case 'udp-endpoint':
      return `udp://${payload.host}:${payload.port}`
    default:
      return payload.text
  }
}

function button(label: string, onClick: () => void | Promise<void>): HTMLButtonElement {
  const el = document.createElement('button')
  el.className = 'btn'
  el.textContent = label
  el.addEventListener('click', () => void onClick())
  return el
}

function renderResultCard(container: HTMLElement, payload: QrPayload, withActions: boolean): void {
  container.replaceChildren()
  const { badge, className, title, rows } = describePayload(payload)
  const card = document.createElement('div')
  card.className = `result ${className}`
  const head = document.createElement('div')
  head.className = 'result-head'
  const badgeEl = document.createElement('span')
  badgeEl.className = 'badge'
  badgeEl.textContent = badge
  const titleEl = document.createElement('strong')
  titleEl.textContent = title
  head.append(badgeEl, titleEl)
  card.append(head)
  for (const [key, value] of rows) {
    const row = document.createElement('div')
    row.className = 'row-line'
    const keyEl = document.createElement('span')
    keyEl.className = 'key'
    keyEl.textContent = key
    const valueEl = document.createElement('span')
    valueEl.className = 'value'
    valueEl.textContent = value
    row.append(keyEl, valueEl)
    card.append(row)
  }
  if (withActions) {
    const raw = payloadRawText(payload)
    const actions = document.createElement('div')
    actions.className = 'row'
    actions.append(button('Copy', () => copyText(raw)))
    if (navigator.share) actions.append(button('Share', () => navigator.share({ text: raw }).catch(() => undefined)))
    actions.append(
      button('Show as QR', () => {
        setGenerateInput(raw)
        switchTab('generate')
      })
    )
    card.append(actions)
  }
  container.append(card)
}

// ---- scanning ---------------------------------------------------------------

let camera: CameraSession | null = null
let detection: Detection | null = null
let wakeLock: WakeLockSentinel | null = null
let delivered = false
let torchOn = false

function stopScan(): void {
  detection?.cancel()
  detection = null
  camera?.stop()
  camera = null
  void wakeLock?.release().catch(() => undefined)
  wakeLock = null
  torchOn = false
  $('#scan-live').hidden = true
  $('#scan-idle').hidden = false
}

async function startScan(): Promise<void> {
  const status = $('#scan-status')
  const error = $('#scan-error')
  error.textContent = ''
  $('#scan-result').replaceChildren()
  $('#scan-idle').hidden = true
  $('#scan-live').hidden = false
  status.textContent = 'starting camera…'
  delivered = false
  try {
    camera = await openCamera($('#scan-video'))
  } catch (e) {
    stopScan()
    const name = e instanceof Error ? e.name : ''
    error.textContent =
      name === 'NotAllowedError'
        ? 'Camera permission denied — allow access, or paste the code below.'
        : 'No usable camera found — paste the code below.'
    return
  }
  wakeLock = await acquireWakeLock()
  const torchBtn = $<HTMLButtonElement>('#torch')
  torchBtn.hidden = !camera.torchSupported
  status.textContent = 'scanning…'
  detection = startDetection($('#scan-video'), text => {
    if (delivered) return // mirror the app's deliver-once gate
    delivered = true
    stopScan()
    const payload = parseQrPayload(text)
    appendHistory({ when: Date.now(), raw: text, kind: payload.kind })
    renderHistory()
    renderResultCard($('#scan-result'), payload, true)
    toast(payload.kind === 'unknown' ? 'Scanned text (no known format)' : 'Scanned — routed below')
  })
}

$('#start-scan').addEventListener('click', () => void startScan())
$('#stop-scan').addEventListener('click', stopScan)

$('#torch').addEventListener('click', async () => {
  if (!camera) return
  torchOn = !torchOn
  try {
    await camera.setTorch(torchOn)
    $('#torch').classList.toggle('on', torchOn)
  } catch {
    toast('Torch is not controllable on this camera')
  }
})

// Wake Lock auto-releases when the tab hides; re-acquire if a scan is still live.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && camera && !wakeLock) {
    void acquireWakeLock().then(w => (wakeLock = w))
  }
})

// Paste path — same router, for machines (or moods) without a camera.
function parsePasted(): void {
  const trimmed = $<HTMLTextAreaElement>('#paste-input').value.trim()
  if (!trimmed) {
    toast('Paste a code first')
    return
  }
  const payload = parseQrPayload(trimmed)
  appendHistory({ when: Date.now(), raw: trimmed, kind: payload.kind })
  renderHistory()
  renderResultCard($('#scan-result'), payload, true)
}

$('#paste-parse').addEventListener('click', parsePasted)

// ---- generate ---------------------------------------------------------------

const qrInput = $<HTMLTextAreaElement>('#qr-input')
const qrCanvas = $<HTMLCanvasElement>('#qr-canvas')
const qrEmpty = $('#qr-empty')
const qrDetails = $('#qr-details')
let renderTimer = 0

function updateQrEmptyState(hasText: boolean, rendered: boolean): void {
  qrCanvas.style.visibility = rendered ? 'visible' : 'hidden'
  qrEmpty.hidden = rendered
  qrEmpty.textContent = hasText ? 'Too long for a single QR code' : 'QR appears here'
}

async function renderGenerate(): Promise<void> {
  const text = qrInput.value.trim()
  if (!text) {
    clearQrCanvas(qrCanvas)
    qrDetails.replaceChildren()
    updateQrEmptyState(false, false)
    return
  }
  let rendered = true
  try {
    await renderQrCanvas(qrCanvas, text)
  } catch {
    rendered = false
  }
  updateQrEmptyState(true, rendered)
  renderResultCard(qrDetails, parseQrPayload(text), false)
}

qrInput.addEventListener('input', () => {
  clearTimeout(renderTimer)
  renderTimer = window.setTimeout(() => void renderGenerate(), 150)
})

function setGenerateInput(text: string): void {
  qrInput.value = text
  void renderGenerate()
}

$('#copy-text').addEventListener('click', async () => {
  const text = qrInput.value.trim()
  if (!text) return toast('Nothing to copy yet')
  await copyText(text)
})

$('#download-png').addEventListener('click', () => {
  if (!qrInput.value.trim()) return toast('Enter text first')
  qrCanvas.toBlob(blob => {
    if (!blob) return toast('Could not export the QR')
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'opusvoice-qr.png'
    link.click()
    URL.revokeObjectURL(url)
    toast('PNG downloaded')
  }, 'image/png')
})

const shareBtn = $('#share-text')
if (navigator.share) {
  shareBtn.hidden = false
  shareBtn.addEventListener('click', () => {
    const text = qrInput.value.trim()
    if (text) void navigator.share({ text }).catch(() => undefined)
  })
}

// ?text=… prefills the generator (handy for "share to web console" flows).
const preset = new URLSearchParams(location.search).get('text')
if (preset) setGenerateInput(preset)

// ---- history ----------------------------------------------------------------

function loadHistory(): HistoryEntry[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(HISTORY_KEY) ?? '[]') as HistoryEntry[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function appendHistory(entry: HistoryEntry): void {
  const rest = loadHistory().filter(e => e.raw !== entry.raw)
  localStorage.setItem(HISTORY_KEY, JSON.stringify([entry, ...rest].slice(0, HISTORY_LIMIT)))
}

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

function timeAgo(when: number): string {
  const minutes = Math.round((when - Date.now()) / 60000)
  if (Math.abs(minutes) < 60) return relative.format(minutes, 'minute')
  const hours = Math.round(minutes / 60)
  if (Math.abs(hours) < 24) return relative.format(hours, 'hour')
  return relative.format(Math.round(hours / 24), 'day')
}

const BADGE_LABELS: Record<QrPayload['kind'], string> = {
  'pinhole-ticket': 'PINHOLE',
  'udp-endpoint': 'UDP',
  unknown: 'TEXT'
}

function renderHistory(): void {
  const list = $('#history-list')
  const entries = loadHistory()
  list.replaceChildren()
  $('#history-empty').hidden = entries.length > 0
  $('#clear-history').hidden = entries.length === 0
  for (const entry of entries) {
    const item = document.createElement('li')
    const badge = document.createElement('span')
    badge.className = `badge mini ${entry.kind === 'pinhole-ticket' ? 'ticket' : entry.kind === 'udp-endpoint' ? 'udp' : 'text'}`
    badge.textContent = BADGE_LABELS[entry.kind]
    const preview = document.createElement('span')
    preview.className = 'preview'
    preview.textContent = entry.raw.length > 64 ? `${entry.raw.slice(0, 64)}…` : entry.raw
    const when = document.createElement('span')
    when.className = 'muted'
    when.textContent = timeAgo(entry.when)
    item.append(badge, preview, when)
    item.addEventListener('click', () => {
      renderResultCard($('#history-detail'), parseQrPayload(entry.raw), true)
      $('#history-detail').scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    })
    list.append(item)
  }
}

// Two-step clear so an accidental tap can't wipe the history.
let clearArmed = false
let clearTimer = 0

$('#clear-history').addEventListener('click', () => {
  const btn = $<HTMLButtonElement>('#clear-history')
  if (!clearArmed) {
    clearArmed = true
    btn.textContent = 'Really clear?'
    clearTimer = window.setTimeout(() => {
      clearArmed = false
      btn.textContent = 'Clear'
    }, 3000)
    return
  }
  clearTimeout(clearTimer)
  clearArmed = false
  btn.textContent = 'Clear'
  localStorage.removeItem(HISTORY_KEY)
  $('#history-detail').replaceChildren()
  renderHistory()
  toast('History cleared')
})

renderHistory()
void renderGenerate()
