# Quick Start

Get Canon Focus Stack running in under five minutes.

---

## Prerequisites

| Tool | Minimum Version |
|---|---|
| [Docker](https://docs.docker.com/get-docker/) | 24 |
| [Docker Compose](https://docs.docker.com/compose/) | 2.20 |

For **development mode** you will also need:

| Tool | Minimum Version |
|---|---|
| Java (JDK) | 21 |
| Maven | 3.9 (or use the included `mvnw` wrapper) |
| Node.js | 20 LTS |
| npm | 10 |

---

## Option 1 — Docker Compose (recommended)

### 1. Clone the repository

```bash
git clone https://github.com/<your-org>/canon-focus-stack.git
cd canon-focus-stack
```

### 2. Start all services

```bash
docker compose up --build
```

This starts:
- **frontend-web** on [http://localhost:3000](http://localhost:3000)
- **camera-service** on [http://localhost:8080](http://localhost:8080)
- **analysis-service** on [http://localhost:8081](http://localhost:8081)
- **PostgreSQL** on port `5432`

The default profile uses the **mock camera**, so no hardware is needed.

### 3. Open the UI

Navigate to [http://localhost:3000](http://localhost:3000) in your browser.

### 4. (Optional) Seed demo data

```bash
./scripts/seed-demo-data.sh
```

This populates the database with sample sessions and copies example frames into
the shared volume so you can explore the gallery without running a real capture.

### 5. Stop

```bash
docker compose down
```

Add `-v` to also remove database volumes:

```bash
docker compose down -v
```

---

## Option 2 — Development Mode

Development mode runs infrastructure in Docker while the application services
run natively for fast iteration and hot-reload.

### 1. Start infrastructure

```bash
./scripts/dev-up.sh
```

This launches PostgreSQL and the mock camera in Docker.

### 2. Start camera-service

```bash
cd camera-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### 3. Start analysis-service

```bash
cd analysis-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### 4. Start frontend-web

```bash
cd frontend-web
npm install
npm run dev
```

The Vite dev server starts on [http://localhost:5173](http://localhost:5173) with
hot module replacement enabled.

---

## Connecting a Real Camera

See [camera-setup.md](camera-setup.md) for instructions on enabling CCAPI on
your Canon camera and pointing the system at it.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `port 8080 already in use` | Stop the conflicting process or change the port in `docker-compose.yml`. |
| Camera not discovered | Ensure the camera and your machine are on the same Wi-Fi network. See [camera-setup.md](camera-setup.md). |
| Database connection refused | Wait a few seconds for PostgreSQL to finish initializing, then retry. |
| `npm install` fails | Delete `node_modules` and `package-lock.json`, then run `npm install` again. |
