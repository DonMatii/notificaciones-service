# Notificaciones Service - Pasteleria My Dreams

Notifications microservice of the **Pasteleria My Dreams** system: consumes `PedidoCreado`
events from the Kafka topic `pedidos` and persists a notification row per order (RF-09).

## Stack

* **Language:** Java 21
* **Framework:** Spring Boot 3.2.5 + Spring Web + Spring Data JPA
* **Messaging:** Apache Kafka (consumer, topic `pedidos`)
* **Database:** MySQL / Amazon AWS RDS
* **Build tool:** Maven

## What it does

1. Listens to the topic `pedidos` with consumer group **`notificaciones`**.
2. Parses the event JSON published by `pedidos-service`
   (`evento`, `id`, `cliente`, `email`, `producto`, `cantidad`, `total`, `fecha`).
3. Persists a `Notificacion` row: `pedidoId`, `cliente`, `email`,
   `asunto` (`Pedido recibido - {pedidoId}`), `cuerpo`, `estado = PENDIENTE`, `fecha`.
4. **Idempotent:** `pedidoId` is unique, so at-least-once redeliveries create
   exactly one row (duplicates are skipped with a debug log).
5. **Poison-pill tolerant:** an unparsable or invalid message is logged and skipped;
   it never throws out of the listener and never blocks the consumer.

## Status of RF-09 (honest)

**The email is NOT sent yet.** There is no SMTP infrastructure, so notifications are
persisted with state **`PENDIENTE`** and stay there. Sending the email is the pending
step of RF-09: it requires an SMTP server, and only then will the state transition to
an envio state (e.g. `ENVIADA`). This is expected, not a bug.

## How to run it locally

Prerequisites: the Kafka broker up (`pedidos-service` repo already provides it):

```powershell
docker start pasteleria-kafka
```

Then:

```powershell
.\mvnw spring-boot:run
```

The service listens on port **8083**.

### Environment variables

Database connection is configured by environment variables with local fallbacks:

| Variable | Local default |
| :--- | :--- |
| `DB_URL` | `jdbc:mysql://localhost:3306/pasteleria_my_dreams?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `DB_USER` | `root` |
| `DB_PASS` | *(empty)* |

### Verification endpoint (demo only)

```http
GET http://localhost:8083/api/notificaciones
```

Lists all notifications, newest first. CORS is open for `http://localhost:5173`
(the local frontend) for verification/demo purposes only.

## Kafka details

| Setting | Value |
| :--- | :--- |
| Topic | `pedidos` (shared with `pedidos-service`) |
| Consumer group | `notificaciones` |
| `auto-offset-reset` | `earliest` (safe: the consumer is idempotent) |
| Broker | `localhost:9092` |

## Tests

```powershell
.\mvnw test
```

Tests are hermetic: they use in-memory H2 and an `@EmbeddedKafka` broker
(see `src/test/resources/application.properties`), so they require **no** running
MySQL or Docker containers. Covered:

* context loads,
* a real producer payload persists exactly one notification,
* the same event published twice still yields exactly one row (idempotency),
* broken payloads are skipped without killing the consumer (poison-pill tolerance).
