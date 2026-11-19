# syntax=docker/dockerfile:1
# mcp-gateway: security proxy in front of PayFlow's MCP servers.  docker build -t mcp-gateway mcp-gateway

# ---- build: compile TypeScript ----------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# ---- run: production dependencies only, non-root ------------------------------------
FROM node:24-alpine
ENV NODE_ENV=production HOST=0.0.0.0 GATEWAY_CONFIG=/app/gateway.config.json AUDIT_FILE=/data/audit.jsonl
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force && mkdir /data && chown node:node /data
COPY --from=build /src/dist ./dist
# The reviewed tool pins ship with the image: a tool that differs from them starts quarantined.
COPY gateway.config.json pins.json ./
USER node
EXPOSE 8085
VOLUME /data
HEALTHCHECK --interval=10s --timeout=3s --retries=6 \
  CMD wget -qO- http://127.0.0.1:8085/health >/dev/null || exit 1
CMD ["node", "dist/main.js"]
