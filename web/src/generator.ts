/**
 * QR rendering for the Generate tab. ECC level L matches the C# receiver's
 * terminal QRs (QRCoder, ECC L) so both sides maximize scannable payload size.
 */
import QRCode from 'qrcode'

export const QR_SIZE = 320

export async function renderQrCanvas(canvas: HTMLCanvasElement, text: string): Promise<void> {
  await QRCode.toCanvas(canvas, text, {
    errorCorrectionLevel: 'L',
    margin: 2,
    width: QR_SIZE,
    color: { dark: '#0F111A', light: '#FFFFFF' }
  })
}

export function clearQrCanvas(canvas: HTMLCanvasElement): void {
  const context = canvas.getContext('2d')
  if (context) context.clearRect(0, 0, canvas.width, canvas.height)
}
