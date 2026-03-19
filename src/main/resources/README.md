# Reminder Bot DB pack

Этот набор файлов рассчитан под текущую модель бота из `main.zip`:
- users / user settings
- tasks catalog
- subscriptions + daily times
- active prompts / эскалации
- sessions
- app meta (`last_update_id`)
- completion records для истории и `/today`

## Что здесь лежит
- `db/changelog/db.changelog-master.yaml` — мастер-чейнджлог Liquibase
- `db/changelog/001-init.yaml` — создание таблиц и индексов
- `scripts/migrate.sh` — запуск Liquibase update
- `scripts/verify_schema.sql` — быстрые запросы для проверки схемы
- `Dockerfile.migrate` — отдельный image для migration job
- `northflank-job.md` — как запускать на Northflank

## Ожидаемые env
- `BOT_DB_URL`
- `BOT_DB_USER`
- `BOT_DB_PASSWORD`
- `BOT_DB_SCHEMA` (необязательно, default `public`)

`BOT_DB_URL` должен быть JDBC-строкой, например:
`jdbc:postgresql://host:5432/database?sslmode=require`

## Почему есть completion_records
В текущем JSON-коде история закрытых дел теряется после удаления prompt.
Таблица `completion_records` нужна для:
- просмотра дел на сегодня со статусами
- отчётности
- отсутствия "догоняния" старых слотов

## Что код нужно будет сделать после перехода на БД
1. Заменить `JsonStore<BotState>` / `JsonStore<Catalog>` на DAO/Repository слой.
2. При `DONE` не только удалять prompt, но и писать запись в `completion_records`.
3. Для `/today` читать `completion_records` + активные prompts.
4. Для recurring задач после закрытия prompt считать следующий **будущий** слот, а не догонять прошлые.
