/**
 * A QR code as inline SVG. Drawn here on the server so the address inside it
 * (sometimes a key, like a personal feed link) never goes to a third-party QR
 * service. Always dark on paper, whatever the theme: that is what scans.
 */
import QRCode from 'qrcode';

/** @param {string} text */
export function qrSvg(text) {
	return QRCode.toString(text, {
		type: 'svg',
		margin: 1,
		errorCorrectionLevel: 'M',
		color: { dark: '#1b1a16', light: '#f5efe1' }
	});
}
