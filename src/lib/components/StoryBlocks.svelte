<script>
	import BlockText from './blocks/BlockText.svelte';
	import BlockPhoto from './blocks/BlockPhoto.svelte';
	import BlockDiptych from './blocks/BlockDiptych.svelte';
	import BlockGallery from './blocks/BlockGallery.svelte';
	import BlockDump from './blocks/BlockDump.svelte';
	import BlockPull from './blocks/BlockPull.svelte';
	import BlockAside from './blocks/BlockAside.svelte';
	import BlockDivider from './blocks/BlockDivider.svelte';
	import BlockHeading from './blocks/BlockHeading.svelte';
	import BlockCode from './blocks/BlockCode.svelte';
	import BlockAudio from './blocks/BlockAudio.svelte';
	import BlockVideo from './blocks/BlockVideo.svelte';

	import { setContext } from 'svelte';

	/**
	 * `slug` is only needed away from the story's own page (a note shown inline
	 * on the home page): audio and video blocks build their address from it.
	 *
	 * `note` marks a story with no title: its photos are shown whole (not cropped
	 * to a frame) and sized to fit the screen, since the photo is the post.
	 * @type {{ blocks: import('#lib/types.js').Block[], slug?: string, note?: boolean }}
	 */
	let { blocks = [], slug = undefined, note = false } = $props();
	setContext('story-slug', () => slug);
	setContext('story-note', () => note);
</script>

<div class="prose-story">
	{#each blocks as block (block.id)}
		{#if block.type === 'text'}
			<BlockText {block} />
		{:else if block.type === 'photo'}
			<BlockPhoto {block} />
		{:else if block.type === 'diptych'}
			<BlockDiptych {block} />
		{:else if block.type === 'gallery'}
			<BlockGallery {block} />
		{:else if block.type === 'dump'}
			<BlockDump {block} />
		{:else if block.type === 'pull'}
			<BlockPull {block} />
		{:else if block.type === 'aside'}
			<BlockAside {block} />
		{:else if block.type === 'divider'}
			<BlockDivider {block} />
		{:else if block.type === 'heading'}
			<BlockHeading {block} />
		{:else if block.type === 'code'}
			<BlockCode {block} />
		{:else if block.type === 'audio'}
			<BlockAudio {block} />
		{:else if block.type === 'video'}
			<BlockVideo {block} />
		{/if}
	{/each}
</div>
