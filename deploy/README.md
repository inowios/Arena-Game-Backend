# Deploying the backend

Single VPS with Docker (same shape as Arena_Backend):

    cp .env.example .env && edit .env          # JWT_SECRET, DB password, OAuth ids, FCM
    docker compose up -d --build               # app on :8080, postgres on 127.0.0.1:5433

CI builds `ghcr.io/<owner>/arena-game-backend:prod` on every push to main; on the server
`APP_IMAGE=ghcr.io/<owner>/arena-game-backend:prod docker compose pull app && docker compose up -d app`.
Put nginx/Caddy with TLS in front and pass `X-Real-IP` (rate limiting keys on it).

Scheduler note: the matchday scheduler assumes a single app instance.
