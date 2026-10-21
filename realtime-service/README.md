# Realtime service

```sh
npm install
JWKS_URL=http://localhost:8080/api/.well-known/jwks.json RABBITMQ_URL=amqp://foc:pass@localhost:5672 npm run dev
```

The shared `RealtimeService` run configuration supplies the local development profile and environment. `JWKS_URL` and `RABBITMQ_URL` are required. The service listens on `PORT` (default `8084`), after the existing backend services on ports `8080` through `8083`. The RabbitMQ consumer receives message bodies and forwards validated events through the Socket.IO functions.

`npm run build` compiles the service; `npm start` runs the compiled output.

`src/index.ts` starts the HTTP server, Socket.IO, and RabbitMQ consumer. Settings and access
token verification live in `src/config.ts` and `src/auth.ts`; event schemas and
parsers live in `src/events.ts`, with RabbitMQ routing in `src/event-subscriptions.ts`.
The Socket.IO and RabbitMQ integrations are in `src/socket.ts` and `src/rabbitmq.ts`.

Connect with Socket.IO at the same origin as the HTTP server. Pass the access token on the handshake:

```js
io({auth: {token}})
```

The handshake verifies ES256 tokens against the user-service JWKS. The token `sub` is the user id, and the socket joins `user:{userId}`.

Publish user JSON to the durable fanout exchange (`user.updates` by default):

```json
{"userId":"user-123","event":"order.updated","data":{"orderId":"order-456","status":"shipped"}}
```

The matching socket receives event `order.updated` with the `data` object.

Supplier changes arrive on the versioned `foc.supplier-changed.v1` exchange. Every connected socket receives `supplier.changed` with `{supplierId, change}`.

Malformed messages are rejected without requeueing. Each instance has an exclusive temporary queue, so an update reaches every instance. Delivery is live and best effort. Clients refetch current state after reconnecting.

## Verification

```sh
npm run build
npm test
```
