# syntax=docker/dockerfile:1
FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

FROM node:22-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

FROM node:22-slim
ENV NODE_ENV=production
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
COPY config ./config
# No secrets are baked in: DEEPGRAM_API_KEY and JWT_SECRET come from the environment
# (Secret Manager on Cloud Run). PORT is read from the environment (Cloud Run sets it).
ENV PORT=8080
EXPOSE 8080
USER node
CMD ["node", "dist/main.js"]
