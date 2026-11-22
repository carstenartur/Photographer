# Run Photographer and capture your first stack

The web application serves the camera controls and capture jobs on port 8080. Docker Compose builds the UI and backend and stores profiles and jobs in PostgreSQL. A mock camera lets you try the workflow before connecting hardware. To open an existing CR3 file directly, see [Java ImageIO](cr3/imageio.md) or [Fiji](cr3/fiji-update-site.md).

## Start with Docker Compose

Install Git, Docker and Docker Compose v2. Run from a shell:

```sh
git clone https://github.com/carstenartur/Photographer.git
cd Photographer
cp .env.example .env
```

Edit `.env` before the first start. Give `FOCUSSTACK_SECURITY_ADMIN_PASSWORD` and `POSTGRES_PASSWORD` separate passwords of your own. The first is the web login for `admin`. Compose passes the second to both PostgreSQL and the application. Leave `CAMERA_MOCK_ENABLED=true` for this walkthrough.

```sh
docker compose up --build -d
docker compose ps
```

The first build downloads Maven and frontend dependencies. The image enables the Java 25 Vector API for [supported CR3 imports](cr3/imageio.md#optional-simd-c-raw-wavelet-lifting-on-jdk-25). When the `focusstack` service reports **healthy**, open [localhost:8080](http://localhost:8080) and sign in as `admin` with your web password. If you changed `APP_PORT` in `.env`, use that port.

## Try a capture without hardware

1. On the dashboard select **Add Camera**. Give the profile a name such as `Desk mock`, choose **Mock** under **Backend Type**, and select **Create**. This profile needs no network address.
2. On **Jobs**, select **Create Job**, select that camera and set **Max Frames** to a small number such as 5. Select **Create** to save the job.
3. In the job list select **Start**. Follow the job ID to see capture progress and inspect its frames. The [illustrated walkthrough](web-interface.md) shows the controls and job details.

For a physical camera, see [Camera setup](camera-setup.md). To stop the stack, run `docker compose down`. Compose keeps the database, image storage and search index in named volumes for the next start.

## Run locally with Maven

From the repository root, use JDK 25 and Maven 3.9+. Include `frontend-web` explicitly to package the UI. Its Maven build installs its own Node.js and runs the locked npm build. The default application profile uses an in-memory H2 database, so local profiles and jobs last only until shutdown.

```sh
mvn -B -pl frontend-web,app -am package -DskipTests -Djava.awt.headless=true
```

Set `FOCUSSTACK_SECURITY_ADMIN_PASSWORD` in the environment of the Java process. For example, in Bash, read it without echoing it or saving it in your shell history:

```bash
read -rs -p 'Photographer admin password: ' FOCUSSTACK_SECURITY_ADMIN_PASSWORD
echo
export FOCUSSTACK_SECURITY_ADMIN_PASSWORD
java --add-modules=jdk.incubator.vector -Djava.awt.headless=true -jar app/target/focusstack.jar
```

The JVM option enables the optional vector backend on a compatible Java 25 runtime and CPU. Omit it for scalar decoding on a custom Java runtime without the module.

Open [localhost:8080](http://localhost:8080) and follow the same capture steps. For frontend hot reload, start a second terminal in `frontend-web` and run `npm ci` followed by `npm run dev`. Use Node.js 24 to match the Maven build. Vite prints its local URL and proxies API requests to the backend on port 8080. See [local development](developer/contributing.md) for PowerShell and verification commands.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Compose asks for `FOCUSSTACK_SECURITY_ADMIN_PASSWORD` | Check that the file is named `.env` in the repository root and contains your value. The app also rejects blank passwords, `focusstack` and `changeme`. |
| No dashboard on port 8080 | Check `docker compose ps` and `docker compose logs focusstack`. If the port is occupied, set a free `APP_PORT` in `.env` and run `docker compose up -d` again. |
| A local job disappears after restarting Java | The default H2 database runs in memory. Use Compose with its PostgreSQL volume for persistent profiles and jobs. |
| A physical camera has no live view | Check connectivity from the backend host and whether the camera advertises live view in its current mode. See [camera capabilities](ccapi-capabilities.md). |
