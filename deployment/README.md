# First container deployment

For a dedicated persistent local PostgreSQL deployment and the internal management workflow, use
[dogfood/README.md](dogfood/README.md). The generic setup below expects an operator-managed database.
Local identity hardening and recovery drills use [dogfood/OPERATIONS.md](dogfood/OPERATIONS.md).

The Compose file runs the standalone database HTTP frontend and its control-job worker as
separate containers. Compose requests Docker to restart either process after it exits. It does not create a
database, install a Titan package, publish a preview, or replace any existing service.

Build and verify the application and management packages, install them in the selected
database, and build the runnable distributions before building images:

```sh
./gradlew titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact controlJobWorkerDistribution
```

Create an absolute deployment directory readable by the unprivileged user declared in the
deployment Dockerfiles. Put the
application and management package-generated frontend descriptors there as
`application.properties` and `management.properties`. Their routine identities must match
the packages installed in the database. Set these environment variables before running
Compose; keep the credentials out of this repository:

```sh
export TITAN_GRAPHQL_DEPLOY_DIR=/absolute/path/to/deployment
export TITAN_GRAPHQL_DIALECT=postgresql
export TITAN_GRAPHQL_JDBC_URL=jdbc:postgresql://database-host:5432/titan
export TITAN_GRAPHQL_JDBC_USERNAME=titan_graphql
export TITAN_GRAPHQL_JDBC_PASSWORD=replace-with-secret
export TITAN_GRAPHQL_ADMIN_ACCESS_TOKEN=replace-with-secret
docker compose -f deployment/compose.yaml build
docker compose -f deployment/compose.yaml up -d
```

The JDBC host must resolve from inside both containers. Compose binds the HTTP port to
`127.0.0.1` on the host by default; set `TITAN_GRAPHQL_HOST_PORT` to change the host port.
The frontend reads descriptors at startup. Restart it after changing a descriptor or preview
registry. The worker validates the installed JDBC management and control-job schema before
polling; an uncaught database or job-processing failure exits the process, and Docker restarts
it. Inspect `docker compose -f deployment/compose.yaml logs worker` when it keeps restarting.
The preview registry is optional and empty by default. The frontend does not trust actor headers
unless `TITAN_GRAPHQL_HTTP_TRUST_REQUEST_CONTEXT_HEADERS=true` is set; enable that only behind a
trusted gateway that supplies the actor identity.

For artifact jobs, place `artifacts.properties` and candidate packages under the mounted
deployment directory. Registry package paths must name directories visible inside the worker
container. To expose a published preview, place its sealed descriptor and registry under that
same directory. Registry values must use container-visible descriptor paths. Set
`TITAN_GRAPHQL_PREVIEW_FRONTEND_REGISTRY=/deploy/previews.properties`, and recreate the
frontend. Run the packaged `publish-preview` command in a one-shot container with a writable
mount for the deployment directory; the long-running services keep their mounts read-only.
The candidate must already be installed and verified in the serving database before publication.
See `docs/database-http-frontend.md` for the candidate build, installation, and publication
commands.

The M4 first-deployment scope assumes no existing service or clients, so it has no migration
or cutover. `./gradlew databaseEngineContainerDeploymentIntegrationTest --console=plain`
installs fresh application and management packages on each supported database dialect, then
checks admin import, validation, artifact generation, and observed-operation review through the
containerized frontend and worker. It kills and restarts the worker under Docker, publishes a
preview from a one-shot control-plane container, recreates the frontend to load the published
registry, and requests the preview through the containerized HTTP route.
