// See https://svelte.dev/docs/kit/types#app.d.ts

declare global {
	namespace App {
		// interface Error {}
		interface Locals {
			isAdmin: boolean;
			/** The signed-in reader (an active contact), or null. */
			reader: import('#lib/server/readers.js').Reader | null;
		}
		// interface PageData {}
		// No Platform: this runs as a plain Node server (adapter-node). Runtime
		// config comes from $app/env/private via src/lib/server/config.js.
	}
}

export {};
