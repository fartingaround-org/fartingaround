import { sveltekit } from '@sveltejs/kit/vite';
import adapter from '@sveltejs/adapter-node';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

// SvelteKit 3: all Kit config lives here, passed flat to sveltekit().
export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			// A plain Node server, run as one container behind a reverse proxy.
			// The origin is derived per request from the proxy's forwarded headers
			// (PROTOCOL_HEADER / HOST_HEADER in docker-compose.yml), so one image
			// serves any domain.
			adapter: adapter({ out: 'build' }),
			// The phone app POSTs multipart uploads with an Origin header matching
			// the site; nothing else cross-origin is expected. Every other origin
			// keeps full CSRF protection.
			csrf: { trustedOrigins: [] }
		})
	]
});
