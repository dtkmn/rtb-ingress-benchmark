FROM node:26.8.1-bookworm-slim AS dependencies

ENV NODE_ENV=production

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

FROM gcr.io/distroless/nodejs26-debian13:nonroot

ENV NODE_ENV=production

WORKDIR /app

COPY --from=dependencies --chown=nonroot:nonroot /app/node_modules ./node_modules
COPY --chown=nonroot:nonroot package.json ./
COPY --chown=nonroot:nonroot src ./src

EXPOSE 8080

CMD ["src/server.js"]
