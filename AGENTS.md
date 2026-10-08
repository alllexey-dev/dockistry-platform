# AGENTS.md

## 1. О проекте (Project Overview)

`dockistry-platform` — backend-платформа для управления Docker-инфраструктурой.
Целевая продуктовая идея описана в `DESCRIPTION.md`: единый API-first сервис для
пользователей, проектов, RBAC, контейнеров, образов, сетей, volumes, секретов,
reverse proxy, деплоя, мониторинга и бэкапов.

Фактически реализованное состояние на текущий момент — MVP-ядро backend:

- регистрация пользователей и вход через JWT;
- access/refresh token flow с ротацией refresh-токенов;
- обязательная email verification перед login;
- постановка email verification писем в persisted job queue;
- initial admin bootstrap через env config или public setup endpoint;
- базовая IAM/RBAC-модель: users, groups, principals, roles, permissions,
  scopes, role bindings;
- разграничение доступа к projects через глобальные и project-scoped
  permissions;
- начальная project-domain логика: создание, чтение, обновление, архивирование,
  удаление и список проектов;
- Managed Services API для хранения project-scoped container configuration как
  durable source of truth;
- Docker agent API: status, global generic-container operations/logs/stats и
  global image list/inspect/pull/remove operations;
- единый формат API-ответов и ошибок через `ApiResponse`;
- PostgreSQL schema management через Flyway migrations.

Главная бизнес-логика сейчас сосредоточена вокруг:

- безопасной auth-модели: пользователь регистрируется, получает verification
  email, подтверждает email кодом или ссылкой, после чего может получать JWT;
- refresh-token lifecycle: refresh token хранится в БД, ротируется при refresh,
  может быть отозван одиночно или через logout-all, защищен optimistic locking;
- IAM: `Principal` может быть user или group, права выдаются через role bindings,
  пользователь получает права как напрямую, так и через группы;
- projects: каждый проект имеет owner principal, отдельный scope и проверяется
  через `PermissionCheckService`;
- managed services: конфигурация будущих контейнеров хранится как project-scoped
  entity `ManagedService`, а не извлекается из одноразовых Docker containers;
- email jobs: отправка verification email не происходит напрямую в auth flow,
  вместо этого создается `EmailSendJob`, который позже забирает scheduler.

Важно: часть возможностей из `DESCRIPTION.md` пока является roadmap, а не
готовым кодом. Перед реализацией новых Docker/containers/secrets/proxy features
проверяй текущее состояние кода, а не считай roadmap уже существующим API.

## 2. Технологический стек (Tech Stack)

Основной стек:

- Kotlin `2.3.20`;
- JDK `21` через Gradle toolchain;
- Spring Boot `4.0.6`;
- Spring Web MVC;
- Spring Security;
- Spring Data JPA/JDBC;
- Hibernate/JPA entities;
- PostgreSQL `18.4`;
- Flyway migrations;
- Gradle wrapper `9.4.1`;
- JWT через `com.auth0:java-jwt`;
- HTTP JSON через Jackson Kotlin module
  (`tools.jackson.module:jackson-module-kotlin`);
- Hibernate JSONB mapping дополнительно использует Jackson 2 Kotlin module,
  потому что встроенный Hibernate `JacksonJsonFormatMapper` работает через
  `com.fasterxml.jackson`;
- validation через `spring-boot-starter-validation` и Jakarta Validation;
- scheduling через Spring `@EnableScheduling` / `@Scheduled`;
- slug generation через `com.github.slugify:slugify` и ICU4J;
- тесты: JUnit 5, Kotlin test, Spring Boot Test, MockMvc, Spring Security Test,
  Testcontainers PostgreSQL.

Ключевые Gradle plugins:

- `kotlin("jvm")`;
- `kotlin("plugin.spring")`;
- `kotlin("plugin.jpa")`;
- `org.springframework.boot`;
- `io.spring.dependency-management`;
- `org.gradle.toolchains.foojay-resolver-convention`.

Runtime/config:

- локальная БД поднимается через `compose.yaml`;
- приложение слушает `server.port=8080`;
- datasource берется из `DB_URL`, `DB_USER`, `DB_PASSWORD`;
- Hibernate работает с `spring.jpa.hibernate.ddl-auto=validate`;
- Flyway включен через `spring.flyway.enabled=true`;
- `.env.example` содержит локальные значения для PostgreSQL;
- Docker image pull timeout настраивается через `DOCKER_IMAGE_PULL_TIMEOUT`
  / `app.docker-agent.image-pull-timeout` в миллисекундах;
- production profile защищен от дефолтного `jwt.secret=secret` через
  `ProductionSecretDefaultsGuard`.

CI:

- `.github/workflows/ci.yml`;
- GitHub Actions на `ubuntu-latest`;
- JDK 21 Temurin;
- основная проверка: `./gradlew clean build`;
- Docker service отдельно не объявлен: интеграционные тесты сами поднимают
  PostgreSQL через Testcontainers.

## 3. Архитектура и структура (Project Structure)

Корень репозитория:

- `build.gradle.kts` — Gradle build, зависимости, Kotlin/JPA настройки,
  `test`, `benchmarkTest`, `dindTest` и `dockerStatsTest` tasks;
- `settings.gradle.kts` — имя root project и Foojay toolchain resolver;
- `compose.yaml` — локальный PostgreSQL;
- `README.md` — быстрый старт, команды запуска и тестирования;
- `DESCRIPTION.md` — продуктовая идея и roadmap;
- `.github/workflows/ci.yml` — CI;
- `scripts/check-staged-independent.sh` — проверка, что staged diff применим
  отдельно от unstaged/untracked изменений;
- `src/main/resources/application.properties` — production/default config;
- `src/test/resources/application.properties` — test config;
- `src/main/resources/db/migration` — Flyway SQL migrations.

Основной package root:

- `io.dockistry.platform`

Главные модули:

- `auth` — registration/login/refresh/logout/email verification;
- `email` — persisted email send jobs и email sender port;
- `iam` — users, groups, principals, roles, permissions, scopes, RBAC checks;
- `projects` — project domain и project permission checks;
- `services` — durable ManagedService configuration для будущих containers;
- `security` — JWT filter, Spring Security config, auth entry point;
- `shared` — общие API DTO, exception handling, config и глобальные
  cross-domain entities вроде `SystemSetting`.

Внутри модулей используется слойность:

- `api` — REST controllers. Здесь должны быть HTTP mappings, DTO validation и
  возврат `ApiResponse<T>`, но не тяжелая бизнес-логика.
- `application` — application services/use cases. Здесь находятся транзакции,
  orchestration, бизнес-правила, permission checks.
- `domain` — JPA entities, enums, domain exceptions, доменные утилиты. В auth
  также лежат DTO в `domain/dto`.
- `infrastructure` — Spring Data repositories, EntityManager queries,
  adapters, property classes, technical integrations.
- `application/ports` — explicit ports-and-adapters граница там, где она уже
  нужна (`RawEmailSender`, `EmailJobHandler`).
- `config` — Spring configuration classes для конкретного модуля.

Текущие domain boundaries:

- `auth` зависит от `iam` для пользователя и от `email` для verification jobs;
- `projects` зависит от `iam` для principals/scopes/permissions;
- `services` зависит от `projects` для project ownership/access guard и от
  `docker` только для Docker-facing config enums вроде restart policy;
- `security` использует `auth.application.JwtTokenProvider` и `iam` user lookup;
- `email` содержит общий job processor/dispatcher и не должен знать детали auth
  сверх конкретного `VerificationEmailJobContext`/handler;
- `shared` не должен зависеть от feature-модулей; feature-модули могут
  использовать shared abstractions/entities, если они действительно
  cross-domain.

Архитектурные паттерны:

- модульный монолит на Spring Boot;
- layered architecture с элементами ports-and-adapters;
- repository pattern через Spring Data JPA;
- application services как граница транзакций;
- typed domain exceptions поверх shared `ServiceException`;
- единый response envelope `ApiResponse`;
- SQL migrations как источник правды для схемы БД;
- injectable `Clock` вместо прямого `Instant.now()`.

## 4. Правила разработки и Code Style (Development Guidelines)

### Общий стиль Kotlin

- Используй Kotlin-идиомы и существующий стиль проекта.
- Имена классов: `PascalCase`.
- Имена функций, переменных и properties: `camelCase`.
- Constants: `UPPER_SNAKE_CASE` только если это реально low-level constant;
  в проекте чаще используются `object SystemPermissionNames` с `const val`.
- Package names — lower-case, под `io.dockistry.platform.<module>`.
- Файлы обычно называются по главному классу/объекту:
  `ProjectService.kt`, `JwtAuthenticationFilter.kt`,
  `EmailVerificationFlowService.kt`.
- Для DTO используй `data class`.
- Для stateless utility допустим `object`, как `ResourceNameNormalizer` или
  `VerificationCodeGenerator`.
- Constructor injection обязателен для Spring beans.
- Не используй field injection в main-коде.
- В тестах `lateinit var` с `@Autowired` уже используется и допустим.
- Предпочитай small private helper methods, если они улучшают читаемость.
- Не используй Kotlin `check`, `require`, `checkNotNull` и `requireNotNull` для
  domain/application ошибок. Бросай подходящий typed service exception; для
  невозможного внутреннего инварианта используй `InternalDataIntegrityException`.
- Не добавляй комментарии к очевидному коду. Добавляй короткий комментарий
  только для сложной бизнес-причины, concurrency edge case или неочевидного
  framework behavior.
- Старайся держать строки в районе 90 символов. Если строка становится длинной,
  перенеси аргументы, SQL, error messages или chained calls. В SQL/лог-сообщениях
  допустимы более длинные строки, если перенос ухудшает читаемость.

В проекте сейчас нет настроенных `.editorconfig`, ktlint, detekt, Spotless,
Prettier, ESLint, Ruff или Black. Стиль поддерживается вручную и проверяется
компиляцией/тестами.

### Spring и application services

- Бизнес-логику держи в `application` services, не в controllers.
- Mutating use cases помечай `@Transactional`.
- Read use cases, которые зависят от lazy JPA relations или consistency внутри
  запроса, тоже могут быть `@Transactional`.
- Controllers должны быть тонкими: принять request, вызвать service, вернуть
  `ApiResponse`.
- Для request body используй `@RequestBody @Valid`.
- Для configuration values используй `@ConfigurationProperties`, а не scattered
  `@Value`.
- Для времени используй injected `Clock`.
- Не вызывай `Instant.now()`/`LocalDateTime.now()` напрямую в бизнес-коде.
- Если добавляешь scheduled behavior, учитывай `@EnableScheduling` в
  `DockistryPlatformApplication` и настраивай интервалы через properties.

### API response и errors

- Успешные JSON endpoints должны возвращать `ApiResponse<T>`.
- Ошибки должны возвращать тот же envelope:
  `data=null`, `errorMessage`, `errorCode`.
- Для ожидаемых бизнес-ошибок создавай domain exception, наследующий один из:
  `BadRequestException`, `UnauthorizedException`, `ForbiddenException`,
  `NotFoundException`, `ConflictException`.
- Не бросай generic `RuntimeException` для user-facing ошибок: он превратится в
  `500 internal_server_error`.
- `InternalDataIntegrityException` используй только для невозможных внутренних
  инвариантов: например, когда ID/username только что был найден, но entity уже
  не находится.
- Для внутренних ошибок, которым нужен безопасный user-facing message и стабильный
  error code, используй typed `InternalServerErrorException`. В отличие от
  `InternalDataIntegrityException`, он намеренно возвращает клиенту свой message/code
  с HTTP 500 и логируется на уровне ERROR.
- Не добавляй `@ExceptionHandler(MethodArgumentNotValidException::class)` в
  `GlobalExceptionHandler`: класс наследуется от `ResponseEntityExceptionHandler`,
  поэтому нужно override-ить framework methods, как уже сделано для
  `handleMethodArgumentNotValid`.
- Если меняешь validation annotations/messages, обновляй
  `AuthValidationIntegrationTests` или добавляй аналогичные negative tests.
- Сохраняй понятные и стабильные `errorCode`: snake_case strings вроде
  `user_already_exists`, `validation_error`, `token_revoked`.

### Security/auth

- Security stateless: CSRF/formLogin/httpBasic/logout отключены.
- Public endpoints сейчас:
  - `/api/auth/**`, кроме `/api/auth/logout-all`;
  - `/api/setup/initial-admin`;
  - `/api/public/**`;
  - `DispatcherType.ERROR`.
- Все остальные endpoints требуют authenticated request.
- Access token используется для authenticated API.
- Refresh token не должен проходить как access token.
- `logout-all` требует access token и отзывает все active refresh tokens
  текущего пользователя.
- Пользователь не может login до email verification.
- Refresh flow должен ротировать refresh token: старый token отзывается, новый
  выпускается.
- Секрет JWT нельзя оставлять дефолтным при `prod` profile.

### Persistence и Flyway

- Схема БД управляется Flyway, а Hibernate только валидирует ее.
- Если меняешь JPA entity mapping, almost always нужно изменить одну из существующих
  миграций – на данном этапе мы просто сносим БД и поднимаем заново. Если же это
  изменения семантически или структурно абсолютно новые и не подходящие ни под одну из
  существующих миграций – тогда нужно создать новую. Все миграции находятся в
  `src/main/resources/db/migration`.
- Не полагайся на Hibernate auto-DDL: `ddl-auto=validate`.
- Новые migration files называй последовательно:
  `V5__short_description.sql`, `V6__...` и т.д.
- SQL schema должна соответствовать Kotlin entity annotations:
  table names, column names, nullability, indexes, unique constraints,
  foreign keys, enum string storage, JSONB columns.
- Для UUID primary keys используется `GenerationType.UUID`, кроме cases вроде
  shared primary key `User`/`Principal`.
- Для JPA relations внимательно задавай owner side, cascade, orphanRemoval,
  fetch type и nullable/optional.
- Для high-value query paths добавляй indexes миграцией, как сделано для role
  bindings и verification challenges.
- Если ловишь `DataIntegrityViolationException` из-за race condition на unique
  constraints, сначала делай domain-level pre-check, затем повтори check в catch
  и только потом пробрасывай неизвестную ошибку.

### IAM/RBAC

- `Principal` — общая IAM identity. Он может быть `USER` или `GROUP`.
- `User` связан с `Principal` через shared primary key.
- `Group` тоже является principal, всё её права передаются всем её members.
- `Scope` задает область действия permission. Сейчас enum `ScopeType` включает
  `GLOBAL`, `PROJECT`, `CONTAINER`, `SECRET`, `VOLUME`, `NETWORK`,
  `PROXY_RULE`, `STACK`. В текущей project-логике активно используются
  `GLOBAL` и `PROJECT`, но IAM-модель уже рассчитана на будущие Docker-related
  resource scopes.
- `Role` может быть system-owned (`isSystem=true`, `project=null`) или
  project-owned (`isSystem=false`, `project != null`).
- `RoleBinding` связывает subject principal, scope и role.
- Permission checks должны учитывать direct user bindings и group-derived
  bindings. Для этого используй `PermissionCheckService`, не обходи его вручную.
- Удаление project role binding/member сериализуется через
  `SecretDelegationPermissionGuard` в транзакции `ProjectMemberService`: сначала
  lock subject principal, для `GROUP` затем чтение member IDs через
  `GroupRepository.findMemberIdsByPrincipalId` и locks всех пользователей по порядку
  UUID. Grant через `assignRole` не требует этого mutex: добавление источника
  permission не нарушает delegation invariant.
- `deleteMember` фиксирует IDs удаляемых bindings под principal locks, исключает
  именно их из effective permission check и удаляет тот же набор одним batch
  DELETE. Не заменяй это удалением всех bindings по principal/project после
  проверки: concurrent grant может стать оставшимся источником permission и
  затем попасть под такое удаление.
- `ProjectMemberService.requirePermissionRevocationAllowed` проверяет наличие
  собственных Secret delegations в проекте и сохранение effective
  `project.secrets.delegate` после исключения удаляемых bindings. Для `GROUP`
  используй один batch-поиск owner IDs с delegations, включая саму группу и её
  пользователей, затем batch permission check через `PermissionCheckService`.
  Состав группы переиспользуется из locking flow; per-member queries запрещены.
  Потеря последнего источника permission возвращает HTTP 409
  `secret_delegation_active` с требованием force-revoke. Batch permission check
  отдельно проверяет существование всех входных principals одним запросом:
  отсутствующий principal является internal data integrity violation.
- Project permission predicates должны идти через `ProjectAccessService` и
  называться как `has*Permission`, потому что они проверяют только наличие
  permission, а не полный action-level доступ.
- Protected project-id operations (`GET/PATCH/archive/delete`) должны сначала
  получать project через `ProjectAccessGuard`: missing project и no-read access
  возвращаются как `project_not_found`, а read-but-no-action возвращается как
  `insufficient_permission_for_operation`.
- Project slug задается при создании и immutable. Project update меняет только
  `name` и `description`, используя `expectedVersion`.
- System role/permission names держи в `SystemRoleNames` и
  `SystemPermissionNames`.
- Bootstrap базовых ролей/прав и единственного `GLOBAL` scope происходит в
  `SystemRolesBootstrapService`.
- Initial admin получает `SystemRoleNames.PROJECT_ADMIN` на `GLOBAL` scope;
  этот scope должен существовать до `InitialAdminService.initializeAdmin`.
- Docker image operations сейчас проверяются только через `GLOBAL` permissions:
  `docker.images.read` для list/inspect и `docker.images.manage` для
  pull/remove. Обе permission входят в `SystemRoleNames.PROJECT_ADMIN`.
- Generic container operations проверяют `docker.generic-containers.manage`
  только на `GLOBAL` scope. Permission входит в `SystemRoleNames.PROJECT_ADMIN`,
  но не в `SystemRoleNames.PROJECT_OPERATOR`.
- Managed Service read operations используют `project.read`, а mutating
  operations используют `projects.services.manage`. Эта permission входит в
  `SystemRoleNames.PROJECT_ADMIN` и `SystemRoleNames.PROJECT_OPERATOR`.
- Race condition при создании initial admin закрывается через shared
  `SystemSetting` с ключом
  `SystemSettingKeys.INITIAL_ADMIN_BOOTSTRAP_EXECUTED`: insert marker'а
  выполняется первым шагом той же транзакции и полагается на primary key в
  таблице `system_settings`.

### Group membership и GLOBAL permission writers

- При добавлении flows изменения group membership или GLOBAL role bindings
  согласуй их concurrency protocol с существующими project binding writers и
  созданием Secret delegations. Все эти flows используют PostgreSQL
  transaction-level advisory locks с ключом только по `principalId` в общем
  namespace; project ID в ключ не входит. Это обеспечивает координацию изменений
  effective permissions сразу в нескольких проектах.
- PostgreSQL advisory lock защищает только участвующие в общем протоколе flows.
  Он не блокирует изменение `group_members` автоматически. Чтение состава группы
  для group binding revoke и все membership writers должны использовать общий
  principal-based mutex группы с одним и тем же ключом по её `principalId`.
- Membership writer и group binding writer должны также сериализоваться с
  direct permission writers и delegation пользователя. Получение locks,
  чтение актуального membership/effective permissions, проверка инвариантов и
  изменение выполняются в одной транзакции. Для нескольких principals соблюдай
  общий стабильный порядок locks, исключающий deadlocks: сначала берутся GROUP
  locks по возрастанию principal IDs, затем USER locks в том же порядке.
- Исключение пользователя из группы и отзыв GLOBAL binding не должны удалять
  последний effective источник `project.secrets.delegate` в любом проекте,
  которому делегированы собственные Secrets затронутого owner. Учитывай оставшиеся
  direct, group-derived и GLOBAL источники. При нарушении возвращай атомарный
  отказ `secret_delegation_active` с требованием force-revoke; частичные изменения
  membership/bindings запрещены.
- При изменении bindings группы под lock её principal прочитай актуальный состав
  и возьми principal-based locks всех её пользователей, включая пользователей
  без delegations. Создание delegation использует тот же lock owner principal.
  Это закрывает гонку с первой delegation в любом проекте; membership writers
  используют общий lock группы и сохраняют её состав во время проверки.
- Добавление этих flows требует concurrency tests на membership removal и GLOBAL
  revoke одновременно с созданием первой delegation, а также на group binding
  revoke одновременно с membership change и direct user binding revoke.

### Email verification и email jobs

- Не отправляй verification email напрямую из auth service.
- Для verification flow создавай/переиспользуй `EmailSendJob` через
  `EmailVerificationJobCreator`.
- `EmailSendJobScheduler` периодически запускает `EmailSendJobProcessor`.
- `EmailSendJobDispatcher` выбирает handler по `contextTypeKey`.
- Для новых типов email jobs добавляй:
  - context data class, реализующий `EmailSendJobContext`;
  - stable `TYPE_KEY`;
  - `EmailJobHandler`;
  - tests на serialization/dispatch/lifecycle, если есть логика.
- `RawEmailSender` — порт. Production sender пока dummy, tests используют
  `InMemoryRawEmailSender`.
- Verification code/hash/link logic находится в auth infrastructure/application;
  не размазывай ее по controllers.

### Docker API

- Generic container endpoints находятся под `/api/docker/containers` и
  представляют host-level API, не привязанный к project scope.
- Все generic container read и mutation flows требуют `GLOBAL` permission
  `docker.generic-containers.manage`.
- Generic container API не должен читать или изменять контейнер с любым label
  из namespace `io.dockistry.*`: list скрывает такие контейнеры, а прямой
  доступ по id возвращает typed 403 с error code
  `dockistry_managed_container_access_forbidden`. Проверяй весь namespace
  через `DockerLabels.containsDockistryLabel`, а не только известную
  комбинацию managed/project/service labels.
- Контейнеры с любым `io.dockistry.*` label принадлежат соответствующим
  Dockistry-managed flows. Не подключай generic lifecycle operations к
  Managed Service operation gate и не используй generic API как обход
  Managed Service lifecycle.
- Контейнеры, созданные из Managed Service, должны дополнительно получать label
  `io.dockistry.service-id=<serviceId>` через `DockerLabels.SERVICE_ID`.
- `ContainerReadService` — общий low-level read-only bean для container list,
  inspect, stats и logs. Он принимает только Docker-level параметры, вызывает
  `ContainerService` и маппит результат в API DTO; permission/ownership/lifecycle
  policy в него не добавляй.
- Generic container read flows должны использовать `ContainerReadService`.
  Managed Service read flows тоже переиспользуют этот bean, выполняя свои
  access checks до вызова.
- `ManagedServiceContainerReadService` находит container по точной комбинации
  project/service labels и делегирует inspect, stats и logs в
  `ContainerReadService`. Runtime state контейнера при поиске не фильтруй:
  invariant Managed Service — не более одного container в любом state.
- Для Managed Service нет отдельного container list endpoint. Отсутствие
  container возвращает `managed_service_not_deployed`, а больше одного
  container для service является internal data integrity violation.
- Managed Service container reads требуют `project.read`; permission check и
  проверка существования service выполняются в `ManagedServiceService` до
  делегирования в `ManagedServiceContainerReadService`.
- Managed Service container read endpoints:
  `GET /api/projects/{projectId}/services/{serviceId}/container`,
  `GET /api/projects/{projectId}/services/{serviceId}/container/stats` и
  `GET /api/projects/{projectId}/services/{serviceId}/container/logs`.
- Generic container service должен использовать общий `ContainerService`;
  прямое обращение к `DockerAgentClient` держи внутри `ContainerService` и
  infrastructure adapter.
- Docker image endpoints находятся под `/api/docker/images`: `GET` list,
  `GET /{imageId}` inspect, `POST /pull` pull и `DELETE /{imageId}` remove.
- `DockerImageService` проверяет `GLOBAL` permissions и идет через общий
  `ContainerService`; прямой `DockerAgentClient` не внедряй в image service.
- `DockerImageSummary` содержит список распарсенных image references.
  `DockerImageInfo` — internal remap inspect output из docker-java.
- Pull image сейчас blocking и ограничен `app.docker-agent.image-pull-timeout`.
  Timeout должен возвращать `docker_image_pull_timeout`.
- Remove image conflict должен возвращать typed error
  `docker_image_rm_conflict`, а не `docker_agent_unavailable`.
- Docker lifecycle state conflicts из docker-java (`NotModifiedException` и
  `ConflictException`) маппь в typed Docker domain exceptions, а не в
  `docker_agent_unavailable`.
- Container lifecycle endpoints:
  `POST /api/docker/containers/{containerId}/start|stop|restart|pause|unpause`,
  `DELETE /api/docker/containers/{containerId}`.
- Container logs endpoint:
  `GET /api/docker/containers/{containerId}/logs`.
- Logs query params: `tail` (default `200`, max `1000`), `since`, `until`,
  `timestamps`, `streams`.
- `since`/`until` принимаются как ISO-8601 timestamps и маппятся на Docker
  seconds range. `since > until`, отрицательный/слишком большой `tail` и
  неизвестные streams должны возвращать `invalid_container_logs_request`.
- `streams` поддерживает `stdout`, `stderr` и `stdout,stderr`; в docker-java это
  маппится на `withStdOut`/`withStdErr`, а stream в ответе берется из
  `Frame.streamType`. Для контейнеров с `Tty=true` Docker может склеивать
  stdout/stderr в `RAW`, поэтому разделение потоков не гарантируется.
- Логи возвращаются через `ApiResponse<ContainerLogs>` как список строк с
  полями `stream` и `line`.

### Managed Services API

- ManagedService хранится в таблице `services` и является captured, durable,
  reusable container configuration.
- Service `name` immutable, уникален внутри проекта, валидируется через общий
  `SlugValidator` как slug до 40 символов.
- ManagedService configuration проходит через raw
  `ManagedServiceConfigurationDraft`; entity принимает только закрытую
  `ManagedServiceConfiguration`, созданную централизованной validation factory.
  Одинаковые annotations между create/update DTO не дублируй.
- `imageRef` нормализуется к `repository:tag`; если tag отсутствует, используется
  `latest`. В entity repository и tag хранятся раздельно; services-domain использует
  собственный `ManagedServiceImageRef`, а не Docker agent model. Custom registries и
  digest refs пока не поддерживаются.
- JSONB поля (`command`, `args`, `env`, `endpoints`, `volumes`) должны оставаться
  типизированными через `@JdbcTypeCode(SqlTypes.JSON)` и
  `@Column(columnDefinition = "jsonb")`.
- `ServiceEndpoint.mode` поддерживает `DIRECT` и `EDGE`: для `DIRECT` требуется
  `externalPort`, для `EDGE` `externalPort` не задается. DIRECT endpoints не могут
  повторять пару `(externalPort, protocol)`, но один номер порта разрешен одновременно
  для TCP и UDP.
- Имена managed volumes содержат минимум два символа и следуют Docker-compatible
  pattern: начинаются с alphanumeric symbol и далее используют только alphanumeric,
  `_`, `.`, `-`.
- `restartMaxRetries` допустим только для `DockerRestartPolicyType.ON_FAILURE`.
- Create выполняется через отдельный transactional executor. Внешний orchestration
  после rollback повторно проверяет project-scoped name при unique-constraint race,
  чтобы конкурентный дубль стабильно возвращал `managed_service_already_exists`.
- Full update требует `expectedVersion`. Stale request возвращает текущее состояние,
  если оно уже совпадает с запрошенным, иначе возвращает
  `409 managed_service_version_conflict`. Реальная optimistic-lock race повторно
  проверяется в новой транзакции после rollback проигравшей записи.
- ManagedService lifecycle использует статусы `ACTIVE`, `STARTING`, `STOPPING`,
  `PAUSING`, `UNPAUSING` и `DELETING`. Deployment begin, update и его conflict
  resolution, runtime lifecycle transitions и начало удаления сериализуются через
  `ManagedServiceLifecycleGuard`:
  guard работает внутри уже открытой транзакции (`Propagation.MANDATORY`) и предоставляет
  две явные политики mutex acquisition — fail-fast через
  `SELECT ... FOR NO KEY UPDATE NOWAIT`
  и bounded wait через transaction-local PostgreSQL `lock_timeout` с обычным
  `SELECT ... FOR NO KEY UPDATE`. Этот lock сохраняет взаимное исключение lifecycle
  writers, но совместим с FK `KEY SHARE` при создании Secret warnings и не создаёт
  цикл ожидания service/delegation между deploy или update и revoke.
  Короткие production lifecycle transitions используют bounded
  wait с default timeout из companion object guard; timeout и несовместимый durable
  lifecycle status возвращают typed conflict. Fail-fast API сохраняется для use cases,
  которым требуется немедленный отказ.
- Start/stop/pause/unpause не держат DB-транзакцию во время Docker I/O. Короткий
  `ManagedServiceRuntimeLifecycleService` transition сначала фиксирует соответственно
  `STARTING`/`STOPPING`/`PAUSING`/`UNPAUSING`, затем runtime executor выполняет Docker
  operation, а отдельный fenced transition по lifecycle status и entity version
  возвращает сервис в `ACTIVE`. Start/stop сразу сохраняют пользовательский
  `desiredState`; pause/unpause его не меняют. При runtime failure lifecycle
  возвращается в `ACTIVE`; при ошибке финализации transient status остается для
  будущего repair.
- Runtime executor и repair transient lifecycle сериализуются через
  `ManagedServiceOperationGate` на session-level PostgreSQL advisory lock. Это
  сознательно не является полноценным fencing: если PostgreSQL session потеряна, пока
  executor продолжает Docker operation, lock может исчезнуть раньше executor. Такой
  узкий failure path принят как допустимый; более сильный runtime fencing сейчас не
  планируется.
- Удаление ManagedService является многофазным flow: короткая транзакция под row lock
  проверяет отсутствие active deployment, сохраняет deletion context и фиксирует
  `ACTIVE -> DELETING`. Затем общий `ManagedServiceDeletionExecutor` под
  `ManagedServiceOperationGate` по service ID читает persisted deletion plan, всегда
  удаляет Docker container и volumes вне DB-транзакции и физически удаляет только строку
  в `DELETING`. Gate удерживается до завершения DB deletion, чтобы отложенный retry не
  удалил одноименный volume нового service. API delete и reaper используют один executor.
  При ошибке cleanup сервис остается в `DELETING` для будущего retry/reaper; list
  показывает `ACTIVE` и все runtime transient statuses, а get разрешает runtime
  transient statuses и для `DELETING` возвращает typed deletion-in-progress conflict.
  `ManagedServiceDto.lifecycleStatus` делает runtime operation видимой клиенту.
- Reaper возвращает застрявшие `STARTING`, `STOPPING`, `PAUSING` и `UNPAUSING`
  services в `ACTIVE`; reconciler (появится позднее) восстанавливает runtime
  согласно desired state, который pause/unpause не меняют.
- Deployment lifecycle использует переходы `IN_PROCESS -> FINISHED`,
  `IN_PROCESS -> FAILED` и `IN_PROCESS -> CANCELING -> CANCELED`. Status,
  `containerId`, deployment `errorMessage` и cleanup diagnostics меняются только
  use-case-specific repository CAS, а не через managed entity setters. Cleanup failure
  оставляет deployment в `CANCELING`, записывает `cleanupErrorMessage` и
  `lastCleanupAttemptAt`; успешный retry очищает cleanup error и завершает cancellation.
- Активными считаются deployment в `IN_PROCESS` и `CANCELING`; partial unique index
  по `service_id` не позволяет начать новый deploy до завершения cancellation cleanup.
  `CANCELED` можно выставлять только после успешной очистки Docker resources.
- Project network и managed volume перед использованием ищутся по точному Docker name,
  после чего их ownership проверяется по project/service labels. Одноименный foreign или
  unlabeled resource нельзя переиспользовать: deploy возвращает typed ownership conflict,
  требующий вмешательства администратора.
- Managed volume получает имя
  `dockistry_<project_slug>_<service_name>_<volume_name>`; immutable project slug и
  immutable service name делают имя стабильным в течение жизни service.
- Project network получает имя `dockistry_<project_slug>`, а managed container —
  `dockistry_<project_slug>_<service_name>`.
- Несколько project networks с одинаковым exact name считаются Docker name ambiguity,
  а не ownership conflict. Если Docker сообщил об успешном создании network/volume, но
  ресурс нельзя сразу перечитать по имени, deploy возвращает typed internal verification
  error с рекомендацией обратиться к администратору.
- Новые managed volumes получают `DockerLabels.forDeployment`; cancellation cleanup
  удаляет только containers и volumes текущего deployment по этим labels. Network является
  project-level resource и в deployment cleanup не входит.
- ManagedService endpoints:
  `GET/POST /api/projects/{projectId}/services` и
  `GET/PATCH/DELETE /api/projects/{projectId}/services/{serviceId}`, включая
  `POST .../{serviceId}/start|stop|pause|unpause`.
- Protected service-id operations сначала проверяют readable project через
  `ProjectAccessGuard`; missing service внутри readable project возвращается как
  `managed_service_not_found`, а недостаток manage permission — как
  `insufficient_permission_for_operation`.

### Validation и DTO

- Request DTO должны иметь Jakarta Validation annotations на fields через
  `@field:...`.
- Username pattern сейчас:
  `^[a-zA-Z0-9._-]{3,32}$`.
- Password pattern сейчас:
  printable ASCII, `8..128` chars.
- Number verification code: ровно 6 digits.
- Email fields используют `@field:NotBlank` и `@field:Email`.
- Validation errors должны сохранять custom messages в response body.

### Tests

- Основная команда перед push:
  `./gradlew clean build`.
- Быстрая команда:
  `./gradlew test`.
- Benchmark-like тесты исключены из обычного `test` и запускаются отдельно:
  `./gradlew benchmarkTest`.
- Docker stats integration tests исключены из обычного `test`/`build`, потому что
  nested Docker-in-Docker stats counters нестабильны на GitHub-hosted runners.
  Если меняешь Docker stats mapping или сбор Docker metrics, локально запускай
  `./gradlew dockerStatsTest`.
- Docker-in-Docker integration tests помечаются `@Tag("dind")`, исключены из
  обычного `test`/`build` и запускаются отдельно через `./gradlew dindTest`:
  они нестабильны из-за сети и pull images внутри nested Docker. Для проверки
  изменений не запускай их в full suite; новые DinD-тесты всегда помечай этим
  тегом. Если меняешь Docker agent adapter или docker-java mapping, локально
  запускай `./gradlew dindTest`.
- Для конкретного теста:
  `./gradlew test --tests "io.dockistry.platform.SomeTest"`.
- Интеграционные тесты используют:
  - `@SpringBootTest`;
  - `@AutoConfigureMockMvc` для API/security;
  - `@Import(TestcontainersConfig::class)`;
  - `@Testcontainers`.
- Testcontainers поднимает PostgreSQL `postgres:18.4-alpine`; локальный
  `docker compose` для тестов не нужен.
- Для email-related tests импортируй `TestEmailSenderConfig`.
- Для deterministic time импортируй `TestClockConfig` и используй
  `MutableClock`.
- Для auth setup в API tests переиспользуй `AuthTestClient`, когда подходит.
- Для IAM/project fixtures используй `IamProjectFixtures`.
- Все concurrency tests используют `testsupport/concurrency/ConcurrencyTestSupport`;
  API и примеры описаны в `src/test/kotlin/io/dockistry/platform/testsupport/concurrency/README.md`.
  В сценариях оставляй service calls, именованные checkpoints, порядок событий и
  business assertions. Executors, latches, PIDs, polling и cleanup держи в testsupport.
  `@Primary` test adapters делегируют реальные production calls и вызывают
  checkpoints в существующих точках; production hooks ради тестов не добавляй.
  `assertBlocked` проверяет ожидание конкретных PostgreSQL sessions, `run` всегда
  освобождает pauses и завершает workers. Проверяй итоговое состояние после
  commit/rollback. Сценарии одного shared helper выполняются последовательно.
- Если меняешь contract:
  - добавляй positive tests для happy path;
  - добавляй negative tests для validation, permissions и error codes;
  - проверяй не только status code, но и `ApiResponse.errorCode`;
  - для protected endpoints тестируй через full Spring context + MockMvc, а не
    standalone MockMvc, чтобы не обойти security filter chain.
- Если меняешь JPA mapping или Flyway migration, минимум запускай context/JPA
  tests или полный `./gradlew test`.

### Git и изменения

- Держи изменения узкими: не смешивай feature, refactor, formatting и docs без
  причины.
- Не трогай unrelated файлы.
- Обновляй `AGENTS.md`, если новые
  решения меняют архитектуру, workflow, правила разработки, тестирования,
  инфраструктуру, модульные границы или важные проектные соглашения.
- Обновляй `README.md`, если изменение влияет на запуск, настройку окружения,
  миграции, тестовые команды, CI, публичное API, пользовательский flow или
  documented behavior.
- Если `AGENTS.md` и `README.md` не нужно менять, осознанно проверь это
  перед финальным ответом.
- Раздел #6 в данном файле может содержать локальный durable product contract
  текущей задачи и не должен превращаться в session notes, implementation status
  или roadmap. Изменения самого раздела #6 не коммить: его committed state —
  placeholder `## 6. Durable product contract текущей задачи`. При изменении
  согласованных инвариантов синхронизируй локальный раздел и публичное issue.
- Перед commit полезно проверять `git diff --check`.
- Если есть staged subset и грязный worktree, используй
  `scripts/check-staged-independent.sh -- ./gradlew test`, чтобы убедиться, что
  staged diff самодостаточен.
- Commit messages лучше писать в conventional style, например:
  `feat(auth): ...`, `fix(api): ...`, `test(projects): ...`, `docs: ...`.

## 5. Инструкция для AI-агента (Agent Workflow / Rules)

- Твоя роль: Senior Developer. Я делегирую тебе задачи, чтобы ускорить
  разработку.
- Сначала прочитай этот файл, затем проверь актуальное состояние нужных файлов
  в репозитории. Не работай только по памяти.
- Не ломай существующую логику. Если нужно сделать масштабный рефакторинг —
  сначала спроси разрешения.
- Пиши чистый, читаемый Kotlin-код в стиле текущего проекта.
- Если добавляешь новую сложную функцию или нетривиальный сервисный flow,
  добавь короткий KDoc/Docstring или точечный комментарий с причиной, а не
  пересказом кода.
- Если в задаче не указано иное, старайся переиспользовать уже существующие в
  проекте утилиты, services, repositories, fixtures и test helpers. Не пиши
  велосипеды.
- Для API сохраняй `ApiResponse` envelope и существующую error-handling модель.
- Для новых бизнес-ошибок добавляй typed domain exception с понятным
  `errorCode`.
- Для JPA entity changes всегда проверяй, нужна ли Flyway migration. В этом
  проекте обычно нужна.
- Для auth/security changes запускай релевантные integration tests, а при
  возможности весь `./gradlew test`.
- Для permission/project changes проверяй group-derived access и existing
  fixture invariants.
- Для email verification changes учитывай job queue, cooldown, rate limit,
  throttle и retry behavior.
- Не ослабляй тесты, чтобы протолкнуть изменение. Если новый restriction
  корректен — обнови fixtures и добавь negative coverage.
- При существенных изменениях не забывай обновлять `AGENTS.md`. Если изменение
  затрагивает команды, конфигурацию, публичное поведение, onboarding или
  эксплуатацию проекта, также обновляй `README.md`.
- Отвечай по делу, без лишней воды. Давай сразу рабочий код и называй команды,
  которыми проверял результат.
- Если проверку не удалось запустить из-за окружения, Docker, Gradle lock или
  sandbox permissions, явно скажи об этом и укажи точную причину.

## 6. Durable product contract текущей задачи

[Здесь я буду описывать текущий фокус разработки перед новой сессией.]
