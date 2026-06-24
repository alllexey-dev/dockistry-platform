# Dockistry Platform

Система управления Docker-инфраструктурой: аутентификация и авторизация (JWT + RBAC),
управление контейнерами, образами, сетями, секретами, reverse proxy и т.д.
Полное описание возможностей и roadmap — в [`DESCRIPTION.md`](./DESCRIPTION.md).

> Текущее состояние: MVP-ядро — регистрация/вход пользователей (JWT, access + refresh токены),
> базовая RBAC-модель (пользователи, группы, роли, права, scope), базовый Projects REST API,
> миграции БД через Flyway.

## Технологический стек

| Компонент      | Версия / технология                                                       |
|----------------|---------------------------------------------------------------------------|
| Язык           | Kotlin 2.3.20                                                             |
| Runtime        | JDK 21 (Gradle toolchain)                                                 |
| Фреймворк      | Spring Boot 4.0.6 (Web MVC, Security, Data JPA/JDBC, Thymeleaf, Actuator) |
| База данных    | PostgreSQL 18.4                                                           |
| Миграции       | Flyway                                                                    |
| Аутентификация | JWT (`com.auth0:java-jwt`)                                                |
| Сборка         | Gradle 9.4.1 (wrapper)                                                    |
| Тесты          | JUnit 5, Testcontainers, Spring Security Test                             |

## Предварительные требования

- **JDK 21+** — нужен для запуска Gradle. Сам проект собирается под Java 21 (toolchain
  при необходимости скачает нужную версию автоматически).
- **Docker** + **Docker Compose** — для локальной БД PostgreSQL и для интеграционных
  тестов (Testcontainers поднимает контейнер с Postgres).
- Gradle ставить отдельно **не нужно** — используется wrapper (`./gradlew`).

## Быстрый старт

### 1. Клонирование и переменные окружения

```bash
git clone <repo-url>
cd dockistry-platform
cp .env.example .env
```

`.env` уже содержит рабочие значения по умолчанию. Переменные:

| Переменная        | Назначение                          | Значение по умолчанию                                |
|-------------------|-------------------------------------|------------------------------------------------------|
| `DB_DATABASE`     | имя БД                              | `dockistry_platform`                                 |
| `DB_USER`         | пользователь БД                     | `dockistry`                                          |
| `DB_PASSWORD`     | пароль БД                           | `dockistry`                                          |
| `DB_URL`          | JDBC URL                            | `jdbc:postgresql://localhost:5432/dockistry_platform`|
| `POSTGRES_*`      | используются Docker Compose         | производные от `DB_*`                                 |

Дополнительно приложение читает (см. `application.properties`):

| Переменная               | Назначение                       | По умолчанию          |
|--------------------------|----------------------------------|-----------------------|
| `JWT_SECRET`             | секрет для подписи JWT           | `secret` (заменить!)  |
| `JWT_ACCESS_EXPIRATION`  | время жизни access-токена, мс    | `3600000` (1 час)     |
| `JWT_REFRESH_EXPIRATION` | время жизни refresh-токена, мс   | `1209600000` (14 дней)|

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

## Projects API

Project endpoints требуют access token и возвращают JSON в envelope `ApiResponse`.
Базовые операции:

| Метод | Endpoint | Назначение |
|-------|----------|------------|
| `GET` | `/api/projects` | список проектов, доступных текущему пользователю |
| `POST` | `/api/projects` | создать проект |
| `GET` | `/api/projects/{id}` | получить project info |
| `PATCH` | `/api/projects/{id}` | обновить основные поля проекта |
| `POST` | `/api/projects/{id}/archive` | архивировать проект |
| `DELETE` | `/api/projects/{id}` | удалить проект, успешный ответ содержит `data="ok"` |

Для операций по конкретному project id отсутствие проекта и отсутствие read-access
оба отображаются как `404 project_not_found`; если read-access есть, но не хватает
права на действие, возвращается `403 insufficient_permission_for_operation`.

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

# Benchmark-like тесты, исключённые из обычного ./gradlew test
./gradlew benchmarkTest
```

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
