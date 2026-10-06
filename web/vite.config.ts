/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

// Served from the repo's GitHub Pages project path: https://iafahim.github.io/OpusVoice/
const BASE = '/OpusVoice/'

export default defineConfig({
  base: BASE,
  build: { target: 'es2022' },
  plugins: [
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'OpusVoice QR Console',
        short_name: 'OpusVoice QR',
        description: 'Scan and share Pinhole connection strings — discovery without typing.',
        theme_color: '#0F111A',
        background_color: '#0F111A',
        display: 'standalone',
        start_url: BASE,
        scope: BASE,
        icons: [
          { src: 'icons/pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icons/pwa-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icons/pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' }
        ]
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        navigateFallback: 'index.html'
      }
    })
  ],
  test: {
    environment: 'node'
  }
})
