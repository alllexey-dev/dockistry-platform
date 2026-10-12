# Dockistry Platform

Система управления Docker-инфраструктурой: аутентификация и авторизация (JWT + RBAC),
управление контейнерами, образами, сетями, секретами, reverse proxy и т.д.
Полное описание возможностей и roadmap — в [`DESCRIPTION.md`](./DESCRIPTION.md).

> Текущее состояние: MVP-ядро — регистрация/вход пользователей (JWT, access + refresh токены),
> базовая RBAC-модель (пользователи, группы, роли, права, scope), базовый Projects REST API,
> Managed Services API для durable container configuration, Docker agent API для локального
> Docker Engine, миграции БД через Flyway.

## Технологический стек

| Компонент      | Версия / технология                                                       |
|----------------|---------------------------------------------------------------------------|
| Язык           | Kotlin 2.3.20                                                             |
| Runtime        | JDK 21 (Gradle toolchain)                                                 |
| Фреймворк      | Spring Boot 4.0.6 (Web MVC, Security, Data JPA/JDBC, Thymeleaf, Actuator) |
| База данных    | PostgreSQL 18.4                                                           |
| Миграции       | Flyway                                                                    |
| Аутентификация | JWT (`com.auth0:java-jwt`)                                                |
| Docker client   | docker-java                                                               |
| Сборка         | Gradle 9.4.1 (wrapper)                                                    |
| Тесты          | JUnit 5, Testcontainers, Spring Security Test                             |

## Предварительные требования

- **JDK 21+** — нужен для запуска Gradle. Сам проект собирается под Java 21 (toolchain
  при необходимости скачает нужную версию автоматически).
- **Docker** + **Docker Compose** — для локальной БД PostgreSQL, Docker API
  endpoints и интеграционных тестов (Testcontainers поднимает контейнер с Postgres).
- Gradle ставить отдельно **не нужно** — используется wrapper (`./gradlew`).

## Быстрый старт

### 1. Клонирование и переменные окружения

```bash
git clone <repo-url>
cd dockistry-platform
cp .env.example .env
```

`.env` уже содержит рабочие значения по умолчанию. Переменные:

| Переменная               | Назначение                       | Значение по умолчанию                                 |
|--------------------------|----------------------------------|-------------------------------------------------------|
| `DB_DATABASE`            | имя БД                           | `dockistry_platform`                                  |
| `DB_USER`                | пользователь БД                  | `dockistry`                                           |
| `DB_PASSWORD`            | пароль БД                        | `dockistry`                                           |
| `DB_URL`                 | JDBC URL                         | `jdbc:postgresql://localhost:5432/dockistry_platform` |
| `POSTGRES_*`             | используются Docker Compose      | производные от `DB_*`                                 |
| `INITIAL_ADMIN_USERNAME` | username первого администратора  | пусто                                                 |
| `INITIAL_ADMIN_EMAIL`    | email первого администратора     | пусто                                                 |
| `INITIAL_ADMIN_PASSWORD` | пароль первого администратора    | пусто                                                 |

Дополнительно приложение читает (см. `application.properties`):

| Переменная               | Назначение                     | По умолчанию           |
|--------------------------|--------------------------------|------------------------|
| `JWT_SECRET`             | секрет для подписи JWT         | `secret` (заменить!)   |
| `JWT_ACCESS_EXPIRATION`  | время жизни access-токена, мс  | `3600000` (1 час)      |
| `JWT_REFRESH_EXPIRATION` | время жизни refresh-токена, мс | `1209600000` (14 дней) |
| `DOCKER_HOST`            | Docker endpoint для docker-java | локальный Docker socket |
| `DOCKER_IMAGE_PULL_TIMEOUT` | timeout pull image операции, мс | `300000` (5 минут) |

> ⚠️ Для любого окружения, кроме чисто локального, обязательно задайте свой `JWT_SECRET`.

### 2. Запуск базы данных

```bash
docker compose up -d
```

Поднимет PostgreSQL на `localhost:5432`. Данные сохраняются в Docker volume
`dockistry_postgres_data`.

Compose читает переменные из `.env` автоматически. Остановить:

```bash
docker compose down          # остановить
docker compose down -v       # остановить и удалить данные
```

### 3. Запуск приложения

Переменные из `.env` нужно экспортировать в окружение перед запуском (Spring Boot
сам `.env` не читает):

```bash
set -a && source .env && set +a
./gradlew bootRun
```

Приложение стартует на [http://localhost:8080](http://localhost:8080).
При старте Flyway автоматически применит все миграции из
`src/main/resources/db/migration`.

### Альтернатива: сборка и запуск JAR

```bash
./gradlew bootJar
set -a && source .env && set +a
java -jar build/libs/dockistry-platform-0.0.1-SNAPSHOT.jar
```

## Проверка работоспособности

После запуска можно проверить публичный эндпоинт:

```bash
curl http://localhost:8080/api/public/ping
# -> pong
```

## Первичная настройка администратора

Первый администратор получает системную роль `project_admin` в `GLOBAL` scope.
Создание защищено DB-marker'ом, поэтому успешно пройти может только один initial
admin bootstrap.

Есть два способа создать первого администратора:

1. Задать все три переменные `INITIAL_ADMIN_USERNAME`, `INITIAL_ADMIN_EMAIL` и
   `INITIAL_ADMIN_PASSWORD` перед стартом приложения.
2. После старта вызвать public endpoint:

```bash
curl -X POST http://localhost:8080/api/setup/initial-admin \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "email": "admin@example.com",
    "password": "password-123"
  }'
```

Endpoint доступен без authentication только до успешного создания первого
администратора. После этого повторные вызовы возвращают `409
initial_admin_already_exists`. Успешный ответ возвращает access/refresh tokens.

## Projects API

Project endpoints требуют access token и возвращают JSON в envelope `ApiResponse`.
Базовые операции:

| Метод    | Endpoint                     | Назначение                                              |
|----------|------------------------------|---------------------------------------------------------|
| `GET`    | `/api/projects`              | список проектов, доступных текущему пользователю        |
| `POST`   | `/api/projects`              | создать проект                                          |
| `GET`    | `/api/projects/{id}`         | получить project info                                   |
| `PATCH`  | `/api/projects/{id}`         | обновить имя и описание проекта                         |
| `POST`   | `/api/projects/{id}/archive` | архивировать проект                                     |
| `DELETE` | `/api/projects/{id}`         | удалить проект, успешный ответ содержит `data="ok"`     |

Управление участниками проекта:

- `GET /api/projects/{id}/members` — список direct members проекта с readable
  role bindings.
- `GET /api/projects/{id}/assignable-roles` — роли, которые можно назначить в
  контексте проекта.
- `POST /api/projects/{id}/members/{principalId}/roles` — назначить роль
  principal-у, возвращает `roleBindingId`.
- `DELETE /api/projects/{id}/members/{principalId}/roles/{roleBindingId}` —
  удалить конкретный role binding, успешный ответ содержит `data="ok"`.
- `DELETE /api/projects/{id}/members/{principalId}` — удалить все role bindings
  участника, успешный ответ содержит `data="ok"`.

Список members показывает только principals с прямыми role bindings на scope
проекта и не разворачивает group membership в отдельных пользователей. Роли для
назначения должны быть системными или принадлежать текущему проекту и содержать
permission `project.read`. Mutating member endpoints требуют permission
`project.members.manage`.

Удаление binding или участника блокируется с `409 secret_delegation_active`,
если оно лишит владельца делегированных этому проекту Secrets последнего
effective источника `project.secrets.delegate`. Учитываются оставшиеся direct,
group-derived и GLOBAL bindings. При удалении bindings группы проверяются её
собственные Secrets и Secrets её пользователей. Перед повторной попыткой нужно
выполнить force-revoke блокирующих delegations; автоматически они не отзываются.

Для операций по конкретному project id отсутствие проекта и отсутствие read-access
оба отображаются как `404 project_not_found`; если read-access есть, но не хватает
права на действие, возвращается `403 insufficient_permission_for_operation`.
Slug задается при создании проекта, ограничен 30 символами и после этого не
изменяется.
`ProjectInfoDto` содержит текущую `version`. `PATCH /api/projects/{id}` принимает
`name`, `description` и обязательный `expectedVersion` из последнего полученного
`ProjectInfoDto`; отрицательная версия возвращает `400 validation_error`.
`description` обрезается по краям, пустое значение сохраняется как `null`;
максимум 1000 символов, NUL и невалидный Unicode запрещены
(`400 invalid_project_description`).

## Secrets API

Secret принадлежит текущему пользователю, а не проекту. Все endpoints требуют
access token и работают только с собственными Secrets владельца: чужой или
несуществующий Secret возвращает `404 secret_not_found`. Values никогда не
возвращаются.

| Метод    | Endpoint                                          | Назначение                                     |
|----------|---------------------------------------------------|------------------------------------------------|
| `GET`    | `/api/secrets`                                    | список собственных Secrets                     |
| `POST`   | `/api/secrets`                                    | создать Secret с initial version, `201`        |
| `GET`    | `/api/secrets/{secretId}`                         | получить собственный Secret                    |
| `PUT`    | `/api/secrets/{secretId}`                         | заменить `name` и `description`                |
| `DELETE` | `/api/secrets/{secretId}`                         | удалить Secret с versions и delegations         |
| `GET`    | `/api/secrets/{secretId}/versions`                | metadata versions, новые первыми               |
| `POST`   | `/api/secrets/{secretId}/versions`                | rotation: новая version из `values`, `201`     |
| `DELETE` | `/api/secrets/{secretId}/versions/{versionId}`    | физически удалить version                      |
| `GET`    | `/api/secrets/{secretId}/usage`                   | managed services, ссылающиеся на Secret        |
| `GET`    | `/api/secrets/{secretId}/delegations`             | проекты, которым делегирован Secret            |
| `POST`   | `/api/secrets/{secretId}/delegate`                | делегировать Secret проекту `projectId`, `201` |
| `DELETE` | `/api/secrets/{secretId}/delegations/{projectId}` | отозвать delegation (идемпотентно)             |

`PUT` принимает `name`, `description` и обязательный `expectedVersion` из последнего
`SecretDto`. Устаревшая версия допускается, только если Secret уже имеет запрошенные
metadata; иначе возвращается `409 secret_metadata_version_conflict`. Имя уникально
среди Secrets владельца (`409 secret_already_exists`).

Version metadata (`SecretVersionDto`) содержит ID version (`version`), `createdAt` и
отсортированные `keys`. Rotation возвращает такой же `SecretVersionDto` для новой
version.

Delegations (`SecretDelegationDto`) содержат `projectId`, `projectName` и `createdAt`,
отсортированы по имени проекта.

Usage (`SecretUsageDto`) — по одной записи на secret-backed environment variable:
`projectId`, `projectName`, `serviceId`, `serviceName`, `secretId`, `key`, `envName` и
`secretVersion` (`null` для latest). Сюда входят и references в проектах с уже
отозванной delegation: они остаются в configuration и блокируют следующий deploy.

Rotation помечает latest references `OUTDATED`, revoke — `DELEGATION_REVOKED`,
удаление version или Secret — `DELETED`. Revoke и удаление не останавливают
работающие containers. Отсутствующая version возвращает
`404 secret_version_not_found`.

Делегированные проекту Secrets доступны участникам проекта с `project.read`,
независимо от владельца:

| Метод | Endpoint                                     | Назначение                                |
|-------|----------------------------------------------|-------------------------------------------|
| `GET` | `/api/projects/{projectId}/secrets`          | Secrets, делегированные проекту           |
| `GET` | `/api/projects/{projectId}/secrets/{secretId}` | один делегированный Secret              |

`ProjectSecretDto` содержит `id`, `name`, `description`, `owner` (`PrincipalInfoDto`),
`delegatedAt`, `versions` (новые первыми) и `usage` только внутри этого проекта.
Нечитаемый проект возвращает `404 project_not_found`, не делегированный проекту
Secret — `404 secret_not_found`.

## Managed Services API

Managed Service — сохраненная project-scoped конфигурация будущего контейнера:
image reference, command/args, env, endpoints, volumes, restart policy, resource
limits и ожидаемое состояние. Это durable source of truth в таблице `services`;
сам Docker container остается runtime artifact.

| Метод    | Endpoint                                       | Назначение                                      |
|----------|------------------------------------------------|-------------------------------------------------|
| `GET`    | `/api/projects/{projectId}/services`           | список managed services проекта                |
| `POST`   | `/api/projects/{projectId}/services`           | создать managed service                         |
| `GET`    | `/api/projects/{projectId}/services/{serviceId}` | получить managed service                      |
| `PATCH`  | `/api/projects/{projectId}/services/{serviceId}` | заменить изменяемую конфигурацию service      |
| `DELETE` | `/api/projects/{projectId}/services/{serviceId}` | удалить managed service, `data="ok"`          |
| `POST`   | `/api/projects/{projectId}/services/{serviceId}/deploy` | создать и запустить container service     |
| `POST`   | `/api/projects/{projectId}/services/{serviceId}/start` | запустить container service                 |
| `POST`   | `/api/projects/{projectId}/services/{serviceId}/stop` | остановить container service                  |
| `GET`    | `/api/projects/{projectId}/services/{serviceId}/container` | inspect container service        |
| `GET`    | `/api/projects/{projectId}/services/{serviceId}/container/stats` | stats container service      |
| `GET`    | `/api/projects/{projectId}/services/{serviceId}/container/logs` | logs container service        |
| `POST`   | `/api/projects/{projectId}/services/{serviceId}/pause` | pause container service               |
| `POST`   | `/api/projects/{projectId}/services/{serviceId}/unpause` | unpause container service           |

Read endpoints требуют `project.read`. Mutating endpoints требуют
`projects.services.manage`; эта permission входит в system roles `project_admin`
и `project_operator`.
Удаление managed service всегда удаляет его Docker container и managed volumes.

Container read endpoints работают с единственным Docker container managed
service независимо от его runtime state. Отдельного container list endpoint для
managed service нет. Если service еще не deployed или его container отсутствует,
возвращается `409 managed_service_not_deployed`. Logs endpoint поддерживает те же
`tail`, `since`, `until`, `timestamps` и `streams`, что и generic container API.

Pause/unpause используют те же permission и serialized lifecycle flow, что
start/stop. Во время Docker operation service показывает соответственно
`PAUSING` или `UNPAUSING`, затем возвращается в `ACTIVE`. Эти операции не меняют
`desiredState`: pause является временным runtime state, а не новым желаемым
состоянием service.

`name` immutable, уникален внутри проекта, валидируется как slug до 40 символов
вроде `example-service-name`. `imageRef` нормализуется к виду `repository:tag`;
если tag не передан, используется `latest`. Repository и tag хранятся раздельно,
но API возвращает каноническую строку. Custom registries и digest refs пока не
поддерживаются. JSONB-поля (`command`, `args`, `env`, `endpoints`, `volumes`)
возвращаются типизированной структурой в `ApiResponse<ManagedServiceDto>`.
Поле `warnings` содержит только активные предупреждения конкретного сервиса
(`OUTDATED`, `DELETED`, `DELEGATION_REVOKED`); при их отсутствии возвращается `[]`.
Каждое предупреждение содержит `id`, `warningType`, `secretId`, `secretKey`,
`secretVersion`, `envName` и `createdAt`, без значений Secret.

Имена volumes содержат от 2 до 30 символов и соответствуют Docker name rules:
первый символ alphanumeric, оставшиеся символы — alphanumeric, `_`, `.` или `-`.
DIRECT endpoints могут использовать одинаковый `externalPort` для разных
протоколов, но пара `(externalPort, protocol)` должна быть уникальна внутри
managed service.

`PATCH` выполняет full replacement изменяемой конфигурации и требует
`expectedVersion` из последнего `ManagedServiceDto`. Если версия устарела, но
запрошенное состояние уже достигнуто, API возвращает актуальный service с `200`.
Если актуальная конфигурация отличается, возвращается
`409 managed_service_version_conflict`.

## Docker API

Docker endpoints требуют access token и работают через локальный Docker Engine
(`LocalDockerAgentClient` поверх docker-java). По умолчанию docker-java использует
локальный Docker socket; для Colima/remote Docker можно задать стандартный
`DOCKER_HOST`.

| Метод | Endpoint                                             | Назначение                         |
|-------|------------------------------------------------------|------------------------------------|
| `GET` | `/api/docker/status`                                 | состояние доступности Docker agent |
| `GET` | `/api/docker/images`                                 | список Docker images               |
| `GET` | `/api/docker/images/{imageId}`                       | inspect-level детали Docker image  |
| `POST` | `/api/docker/images/pull`                           | pull Docker image                  |
| `DELETE` | `/api/docker/images/{imageId}`                    | удалить Docker image               |
| `GET` | `/api/docker/containers`                              | список generic-контейнеров Docker host |
| `GET` | `/api/docker/containers/{containerId}`                | inspect-level детали контейнера    |
| `GET` | `/api/docker/containers/{containerId}/stats`          | текущие CPU/memory/network stats   |
| `GET` | `/api/docker/containers/{containerId}/logs`           | последние строки логов контейнера  |
| `POST` | `/api/docker/containers/{containerId}/start`         | запустить контейнер                |
| `POST` | `/api/docker/containers/{containerId}/stop`          | остановить контейнер               |
| `POST` | `/api/docker/containers/{containerId}/restart`       | перезапустить контейнер            |
| `POST` | `/api/docker/containers/{containerId}/pause`         | поставить контейнер на паузу       |
| `POST` | `/api/docker/containers/{containerId}/unpause`       | снять контейнер с паузы             |
| `DELETE` | `/api/docker/containers/{containerId}`             | удалить контейнер                  |

Generic container endpoints работают с контейнерами всего Docker host и требуют
GLOBAL permission `docker.generic-containers.manage`. Эта permission входит только
в system role `project_admin`; project-scoped binding не дает доступ к host-level
операциям. Контейнеры с любым label из namespace `io.dockistry.*` исключаются из
списка, а прямое обращение к ним возвращает
`403 dockistry_managed_container_access_forbidden`.

Если Docker Engine недоступен, Docker operations возвращают
`503 docker_agent_unavailable`. Если контейнер отсутствует, возвращается
`404 docker_container_not_found`.

Повторная или несовместимая lifecycle operation возвращает `409` с явным
state-specific `errorCode`: `docker_container_already_started`,
`docker_container_already_stopped`, `docker_container_already_paused`,
`docker_container_not_paused`, `docker_container_not_running` или fallback
`docker_container_lifecycle_conflict`.

Container logs endpoint поддерживает query params:

- `tail` — сколько последних строк вернуть, по умолчанию `200`, максимум `1000`;
- `since` — вернуть логи начиная с ISO-8601 timestamp;
- `until` — вернуть логи до ISO-8601 timestamp;
- `timestamps` — включить runtime timestamp в каждой строке;
- `streams` — `stdout`, `stderr` или `stdout,stderr`.

Ответ возвращается как `ApiResponse<ContainerLogs>` со строками вида
`{ "stream": "STDOUT", "line": "..." }`. Для TTY-контейнеров Docker может
вернуть объединенный `RAW` stream. Некорректные параметры возвращают
`400 invalid_container_logs_request`.

Image endpoints сейчас работают только через `GLOBAL` permissions:

- `docker.images.read` — список и inspect images;
- `docker.images.manage` — pull и remove images.

Обе permission входят в system role `project_admin`. Pull endpoint принимает body:

```json
{
  "repository": "nginx",
  "tag": "latest"
}
```

Успешные pull/remove ответы возвращают `data="ok"`. Timeout pull operation
возвращает `503 docker_image_pull_timeout`; конфликт удаления image без `force`
возвращает `409 docker_image_rm_conflict`. Для принудительного удаления используйте
`DELETE /api/docker/images/{imageId}?force=true`.

## Тестирование

Тесты используют **Testcontainers**, поэтому для их запуска должен быть запущен
**Docker** (отдельно поднимать `docker compose` не нужно — контейнер с Postgres
создаётся автоматически).

```bash
# Все тесты
./gradlew test

# Конкретный класс
./gradlew test --tests "io.dockistry.platform.service.UserServiceTests"

# По шаблону имени
./gradlew test --tests "*JwtAuthentication*"

# Docker-in-Docker tests, исключённые из обычного ./gradlew test
./gradlew dindTest

# Docker stats tests, исключённые из обычного ./gradlew test
./gradlew dockerStatsTest

# Benchmark-like тесты, исключённые из обычного ./gradlew test
./gradlew benchmarkTest
```

`dockerStatsTest` запускает Docker-in-Docker smoke tests для CPU/network/disk stats.
Они исключены из обычного `test`/`build`, потому что Docker stats counters в nested
Docker/cgroup окружениях, включая GitHub-hosted runners, могут быть нестабильными.
Если меняется Docker stats mapping или сбор Docker metrics, локально запускайте
`./gradlew dockerStatsTest`.

`dindTest` запускает Docker-in-Docker integration tests (`@Tag("dind")`) для Docker
agent adapter. Они исключены из обычного `test`/`build`, потому что зависят от сети и
pull images внутри nested Docker и поэтому нестабильны. Docker stats tests в
`dindTest` не входят.

> **Colima вместо Docker Desktop.** Если Docker запущен через Colima, Testcontainers
> может не найти сокет (ошибка `Could not find a valid Docker environment`), так как
> он не читает контексты Docker CLI. Перед запуском тестов задайте:
>
> ```bash
> export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
> export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
> ```
>
> `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` нужен для контейнера-reaper'а Ryuk — это путь
> сокета внутри Colima-VM. Чтобы не задавать каждый раз, добавьте обе строки в `~/.zshrc`.

HTML-отчёт о прогоне: `build/reports/tests/test/index.html`.

## Полезные команды Gradle

```bash
./gradlew build          # полная сборка с тестами
./gradlew build -x test  # сборка без тестов
./gradlew bootRun        # запуск приложения
./gradlew bootJar        # сборка исполняемого JAR
./gradlew clean          # очистка build/
```
