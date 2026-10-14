# Concurrency tests

Все сценарии используют `ConcurrencyTestSupport`. В тесте остаются реальные service
calls, порядок событий и проверки результата. Потоки, таймауты, PostgreSQL PIDs,
ожидания и cleanup находятся в testsupport.

## Сценарий с блокировкой

```kotlin
concurrency.run {
    val beforeSave = pauseAt(BEFORE_DELEGATION_SAVE, matching = secretId)
    val first = start("first delegation") { secretService.delegate(...) }
    beforeSave.awaitReached()

    val principalLock = observeNext(BEFORE_PRINCIPAL_LOCK)
    val second = start("second delegation") { secretService.delegate(...) }
    assertBlocked(principalLock, by = beforeSave)

    beforeSave.release()
    assertEquals(first.await(), second.await())
}
```

`pauseAt` останавливает участвующие операции в указанной точке. `matching`
ограничивает остановку конкретным ресурсом, `participants` позволяет дождаться
нескольких операций, `skip` пропускает первые вызовы этой точки. После `release`
точка остаётся открытой до конца сценария.

`Pause` хранит только первый `Hit` через `AtomicReference`: следующие участники
не перезаписывают его. Их достижения отдельно считает latch `reached`.
Проверки PID/key используют первый `Hit`, а не все события паузы.

`observeNext` записывает следующий вызов без остановки. `assertBlocked` проверяет,
что именно эта PostgreSQL-сессия ждёт сессию, остановленную через `pauseAt` или
удерживающую lock через `holdInTransaction`. Это проверка реального ожидания в БД.

`start` не добавляет транзакцию вокруг service call: транзакционные границы остаются
production-границами. `await()` возвращает значение или бросает исходную ошибку;
`failure()` требует ошибку; `outcome()` возвращает `Result` для нескольких исходов.
Имена операций используются в сообщениях о таймаутах.

## Другие способы координации

- `startTogether` одновременно выпускает операции со старта. Для гонки после
  конкретного pre-check используй `pauseAt(..., participants = N)`.
- `holdInTransaction` захватывает ресурс в отдельной транзакции и удерживает её
  до освобождения. `release()` ждёт завершения держателя и commit.
- `holdExecutionGateConnections` занимает выделенный connection pool;
  `holdExecutionGateLock` удерживает конкретный gate lock;
  `holdExecutionGateLockAt` удерживает ключ, записанный остановленным checkpoint.
  Другие вызовы gate не могут изменить этот ключ.
- `ExecutionGateTestSupport` предоставляет capacity и проверки состояния пула,
  скрывая Hikari/config/MXBean от сценариев.
- `inNewTransaction` используется для committed fixtures и readback;
  `inRolledBackTransaction` проверяет поведение при rollback.
- `withAdvisoryLockProbe` проверяет доступность advisory lock с другой connection.

`run` в `finally` освобождает все остановки и завершает работников. Забытый
`await` не скрывает ошибку работника. Ошибка cleanup добавляется как suppressed
к исходной ошибке сценария. Число `workers` должно вместить все одновременно
работающие операции, включая держателей ресурсов.

## Подключение и новые точки

- `ConcurrencyTestConfiguration`: общий helper без подмены production beans.
- `RepositoryConcurrencyTestConfiguration`: делегирующие `@Primary` repositories
  и principal lock; импортирует общий helper.
- `ClockConcurrencyTestConfiguration`: дополнительно фиксированные часы с точкой
  `CLOCK_INSTANT`. Её бизнес-смысл зависит от вызывающего flow; объясняй его в тесте.
- `ManagedServiceConcurrencyTestConfiguration`: часы, fixture замены deployment
  и Docker adapter с пустым списком контейнеров.
- `DeploymentConcurrencyTestConfiguration`: Docker fake и управляемый execution gate.

Точки перечислены в `ConcurrencyPoint`. Адаптер вызывает `checkpoint` до/после
реального действия. Невооружённые точки, fixture-поток и фоновые потоки проходят
сразу. Production hooks ради тестов не добавляются. Сценарии одного shared helper
выполняются последовательно.

`matching` сравнивается со значением, переданным адаптером:

| Точка | Значение для matching |
| --- | --- |
| `BEFORE_SECRET_SAVE`, `AFTER_SECRET_NAME_CHECK` | Secret name |
| `BEFORE_SECRET_ROW_LOCK`, `BEFORE_SECRET_DELEGATIONS_LOCK`, `AFTER_SECRET_DELEGATIONS_LOCK` | Secret ID |
| `BEFORE_PROJECT_SAVE` | Owner principal ID |
| `BEFORE_DELEGATION_SAVE` | Secret ID |
| `BEFORE_DELEGATION_ROW_LOCK` | Project ID |
| `BEFORE_BINDING_DELETE` | Role binding ID, включая batch delete |
| `AFTER_BINDING_SNAPSHOT` | Subject principal ID |
| `BEFORE_SERVICE_ROW_LOCK`, `AFTER_ACTIVE_WARNINGS_READ` | Service ID |
| `BEFORE_PRINCIPAL_LOCK`, `BEFORE_EXECUTION_GATE_LOCK` | Advisory lock key |
| Clock, reference flush, Docker points | Без значения; используются без matching |
