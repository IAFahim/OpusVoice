// Renders the PWA icons: a scannable QR of this site itself, white on brand-dark modules.
// Run once via `npm run icons`; the PNGs are committed so CI never needs this step.
import { mkdirSync } from 'node:fs'
import { toFile } from 'qrcode'

const SITE = 'https://iafahim.github.io/OpusVoice/'
mkdirSync('public/icons', { recursive: true })
const options = (width) => ({
  width,
  margin: 2,
  errorCorrectionLevel: 'L',
  color: { dark: '#0F111A', light: '#FFFFFF' }
})

await toFile('public/icons/pwa-192.png', SITE, options(192))
await toFile('public/icons/pwa-512.png', SITE, options(512))
console.log('icons written to public/icons/')
