# Dockistry Platform

Система управления Docker-инфраструктурой: аутентификация и авторизация (JWT + RBAC),
управление контейнерами, образами, сетями, секретами, reverse proxy и т.д.
Полное описание возможностей и roadmap — в [`DESCRIPTION.md`](./DESCRIPTION.md).

> Текущее состояние: MVP-ядро — регистрация/вход пользователей (JWT, access + refresh токены),
> базовая RBAC-модель (пользователи, группы, роли, права, scope), базовый Projects REST API,
> read-only Docker agent API для локального Docker Engine, миграции БД через Flyway.

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
- **Docker** + **Docker Compose** — для локальной БД PostgreSQL, read-only Docker API
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
| `PATCH`  | `/api/projects/{id}`         | обновить основные поля проекта                          |
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

Для операций по конкретному project id отсутствие проекта и отсутствие read-access
оба отображаются как `404 project_not_found`; если read-access есть, но не хватает
права на действие, возвращается `403 insufficient_permission_for_operation`.

## Docker API

Docker endpoints требуют access token и работают через локальный Docker Engine
(`LocalDockerAgentClient` поверх docker-java). По умолчанию docker-java использует
локальный Docker socket; для Colima/remote Docker можно задать стандартный
`DOCKER_HOST`.

| Метод | Endpoint                                             | Назначение                         |
|-------|------------------------------------------------------|------------------------------------|
| `GET` | `/api/docker/status`                                 | состояние доступности Docker agent |
| `GET` | `/api/projects/{projectId}/containers`               | список контейнеров проекта         |
| `GET` | `/api/projects/{projectId}/containers/{containerId}` | inspect-level детали контейнера    |
| `GET` | `/api/projects/{projectId}/containers/{containerId}/stats` | текущие CPU/memory/network stats |
| `GET` | `/api/projects/{projectId}/containers/{containerId}/logs` | последние строки логов контейнера |
| `POST` | `/api/projects/{projectId}/containers/{containerId}/start` | запустить контейнер |
| `POST` | `/api/projects/{projectId}/containers/{containerId}/stop` | остановить контейнер |
| `POST` | `/api/projects/{projectId}/containers/{containerId}/restart` | перезапустить контейнер |
| `POST` | `/api/projects/{projectId}/containers/{containerId}/pause` | поставить контейнер на паузу |
| `POST` | `/api/projects/{projectId}/containers/{containerId}/unpause` | снять контейнер с паузы |
| `DELETE` | `/api/projects/{projectId}/containers/{containerId}` | удалить контейнер |

Project container endpoints требуют `project.read`. Контейнер считается контейнером
проекта только при наличии labels:

- `io.dockistry.managed=true`
- `io.dockistry.project-id=<projectId>`

Если Docker Engine недоступен, Docker operations возвращают
`503 docker_agent_unavailable`. Если контейнер отсутствует или не принадлежит
проекту по labels, возвращается `404 docker_container_not_found`.

Lifecycle endpoints дополнительно требуют permission `project.containers.manage`.
Эта permission входит в system roles `project_admin` и `project_operator`.

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
