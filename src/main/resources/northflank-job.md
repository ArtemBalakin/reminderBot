# Northflank migration job

## Build type
Выбирай `Dockerfile`.

## Dockerfile path
`Dockerfile.migrate`

## Runtime variables
- `BOT_DB_URL` = `JDBC_POSTGRES_URI`
- `BOT_DB_USER` = `USERNAME`
- `BOT_DB_PASSWORD` = `PASSWORD`
- `BOT_DB_SCHEMA` = `public`

Для внешнего доступа к БД из локали используй `EXTERNAL_JDBC_POSTGRES_URI`,
но для migration job внутри Northflank лучше использовать **внутренний** `JDBC_POSTGRES_URI`.

## Запуск
1. Создай job
2. Build type: Dockerfile
3. Dockerfile path: `Dockerfile.migrate`
4. Runtime mode: default configuration
5. Сначала прогоняй вручную
6. Потом можно встраивать в release flow
