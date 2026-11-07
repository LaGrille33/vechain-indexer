FROM node:22-bookworm-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY --chown=node:node src ./src
COPY --chown=node:node sql ./sql
COPY --chown=node:node source-manifest.json ./
USER node
EXPOSE 8788
CMD ["node", "src/server.mjs"]
