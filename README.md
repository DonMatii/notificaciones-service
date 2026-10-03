# 🔔 Notificaciones Service - Pastelería My Dreams

Microservicio de notificaciones del sistema **Pastelería My Dreams**: consume los eventos `PedidoCreado` del topic `pedidos` de Kafka y persiste una notificación por cada pedido recibido (RF-09, parcial).

## 📌 Versiones del proyecto

| Rama | Versión | Contenido |
| :--- | :--- | :--- |
| `version-1` | **Entrega 1** | Sin notificaciones. |
| `version-2` | **Entrega 2** | Consumidor Kafka del topic `pedidos` que persiste notificaciones. |
| `version-3` | **Unidad 3** | Pendiente. |

`main` siempre lleva el último avance del desarrollo.

## 🏢 Equipo de Desarrollo
Diseñado y construido por **8 Digital**.

## 🛠️ Stack Tecnológico
* **Lenguaje:** Java 21
* **Framework:** Spring Boot 3.2.5 + Spring Web + Spring Data JPA (Hibernate)
* **Mensajería:** Apache Kafka (consumidor, topic `pedidos`)
* **Base de Datos:** MySQL local / Amazon AWS RDS
* **Gestor de dependencias:** Maven
* **Estructura de datos:** JSON

## 🚀 Endpoints Disponibles

| Método HTTP | Ruta | Descripción |
| :--- | :--- | :--- |
| `GET` | `/api/notificaciones` | Lista todas las notificaciones registradas, las más recientes primero. |

Solo lectura: es un endpoint mínimo de verificación/demo del consumidor. CORS está abierto para `http://localhost:5173` (el frontend local), solo con fines de verificación.

Respuesta (entidad `Notificacion`):

```json
[
  {
    "id": 1,
    "pedidoId": 1,
    "cliente": "E2E Test",
    "email": "e2e@test.cl",
    "asunto": "Pedido recibido - 1",
    "cuerpo": "Hola E2E Test,\n\nTu pedido 1 fue recibido correctamente.\n\nProductos: Torta E2E\nCantidad total de articulos: 2\nTotal: $30000\n\nEstado: pendiente de confirmacion. Enviaremos la confirmacion por correo electronico cuando el pedido sea despachado.",
    "estado": "PENDIENTE",
    "fecha": "2026-10-01T21:29:02.500"
  }
]
```

## 🔄 Qué hace al recibir un evento

1. Escucha el topic `pedidos` con el consumer group **`notificaciones`** (`@KafkaListener`, `auto-offset-reset: earliest`).
2. Parsea el JSON publicado por `pedidos-service`
   (`evento`, `id`, `cliente`, `email`, `producto`, `cantidad`, `total`, `fecha`).
3. Persiste una fila `Notificacion`: `pedidoId`, `cliente`, `email`,
   `asunto` (`Pedido recibido - {pedidoId}`), `cuerpo`, `estado = PENDIENTE`, `fecha`.
4. **Idempotente:** `pedidoId` tiene restricción `UNIQUE`, así que una reentrega
   at-least-once del mismo pedido produce una sola fila (los duplicados se saltan con un log de debug).
5. **Tolerante a mensajes basura (poison pill):** un mensaje ilegible o inválido se
   registra en el log y se salta; el listener nunca lanza excepción hacia afuera y no bloquea al consumidor.

### Cuerpo de la notificación (ejemplo real del E2E)

Asunto: `Pedido recibido - 1`. Cuerpo en texto plano:

```
Hola E2E Test,

Tu pedido 1 fue recibido correctamente.

Productos: Torta E2E
Cantidad total de articulos: 2
Total: $30000

Estado: pendiente de confirmacion. Enviaremos la confirmacion por correo electronico cuando el pedido sea despachado.
```

## 📧 Estado honesto de RF-09: el correo AÚN NO se envía

**El servicio de correo no está implementado.** No existe infraestructura SMTP, así que
las notificaciones se persisten con estado **`PENDIENTE`** y quedan ahí. El envío del correo
es el paso pendiente de RF-09: requiere un servidor SMTP (previsto para cuando haya mail
server en EC2) y solo entonces el estado pasará a un estado de envío (por ejemplo, `ENVIADA`).
Esto es esperado, no es un bug.

## 🐳 Kafka local (broker)

El broker vive en el repositorio de `pedidos-service` (no hay compose en este repo):

```powershell
docker start pasteleria-kafka
# o, si el contenedor no existe todavía, desde el repo pedidos-service:
docker compose up -d
```

| Setting | Valor |
| :--- | :--- |
| Topic | `pedidos` (compartido con `pedidos-service`) |
| Consumer group | `notificaciones` |
| `auto-offset-reset` | `earliest` (seguro: el consumidor es idempotente) |
| Broker | `localhost:9092` |

## ⚙️ Variables de entorno

La conexión a la base de datos se configura por variables de entorno, con defaults locales:

| Variable | Default local |
| :--- | :--- |
| `DB_URL` | `jdbc:mysql://localhost:3306/pasteleria_my_dreams?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `DB_USER` | `root` |
| `DB_PASS` | *(vacío)* |

Otras propiedades relevantes (`src/main/resources/application.properties`): `server.port=8083`, `spring.kafka.bootstrap-servers=localhost:9092`, `app.kafka.topic=pedidos`, `spring.jpa.hibernate.ddl-auto=update` (crea la tabla `notificaciones` al arrancar).

## ▶️ Cómo correrlo local

Requisitos: JDK 21, MySQL 8 local con la base `pasteleria_my_dreams` y el broker Kafka arriba:

```powershell
docker start pasteleria-kafka
.\mvnw.cmd spring-boot:run   # servicio en el puerto 8083
```

## 🧪 Tests

```powershell
.\mvnw.cmd test
```

Los tests son herméticos: usan H2 en memoria y un broker embebido (`@EmbeddedKafka`,
ver `src/test/resources/application.properties`), así que **no** requieren MySQL ni
contenedores de Docker en marcha. Cobertura:

* carga de contexto,
* un payload real de productor persiste exactamente una notificación,
* el mismo evento publicado dos veces sigue produciendo una sola fila (idempotencia),
* payloads rotos se saltan sin matar al consumidor (tolerancia a poison pill).

## 🔄 Flujo end-to-end

```
Frontend (formulario)
   │  POST /api/pedidos
   ▼
pedidos-service (8082)
   │  persiste el pedido y publica PedidoCreado
   ▼
Kafka topic `pedidos`
   ├──▶ notificaciones-service (8083)  → persiste la notificación (estado PENDIENTE)
   └──▶ estadisticas-service  (8081)   → actualiza pedidosTotales / montoTotalPedidos
```

## Seguridad (v2)

- `GET /api/notificaciones` está **protegido con header `X-Api-Key`**: el listado contiene nombres y emails de clientes (datos personales), por lo que no puede ser público.
- La clave vive en la variable de entorno `APP_ADMIN_API_KEY` (propiedad `app.admin-api-key`). Sin configurar, el endpoint responde `503` (fail-closed).
- El consumidor de Kafka no expone endpoints: solo persiste las notificaciones con estado `PENDIENTE`.
