FROM node:26.5.0-alpine3.24
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src src

EXPOSE 8080

CMD ["node", "src/server.js"]
