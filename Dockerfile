# fartingaround — one Node container (SvelteKit, adapter-node).
# Published as a ready-made image by .github/workflows/image.yml; docker compose
# can still build it on the host (see docs/deploy.md).
FROM node:22-alpine AS build
WORKDIR /app
# corepack bundled with node images can predate npm's signing-key rotation;
# update it before letting it fetch the pnpm pinned in package.json.
RUN npm install -g corepack@latest && corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build && pnpm prune --prod --ignore-scripts

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/build ./build
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/package.json ./package.json
# Migrations are applied at startup from here (src/lib/server/db.js).
COPY --from=build /app/db ./db
# One-off tools that run inside the container (the Cloudflare import).
COPY --from=build /app/scripts ./scripts
RUN mkdir -p /data && chown -R node:node /data /app
USER node
# Behind a reverse proxy: trust its forwarded headers to work out the origin.
ENV PORT=3000 HOST=0.0.0.0 DATA_DIR=/data \
    PROTOCOL_HEADER=x-forwarded-proto HOST_HEADER=x-forwarded-host \
    BODY_SIZE_LIMIT=2G \
    MALLOC_ARENA_MAX=2
EXPOSE 3000
VOLUME ["/data"]
CMD ["node", "build"]
