# Reminder Bot v7

Java 21 + Maven Telegram-бот для бытовых дел и напоминаний.

## Что нового в этой версии

- импорт каталога **JSON-файлом**, а не текстом в чате
- создание новых дел **обычными сообщениями** через `/new`
- настройка даты и времени через **Telegram Mini App**
- ежедневные дела могут иметь **несколько времён**
- на напоминание можно ответить кнопками:
  - `Сделал`
  - `Пошёл делать`
  - `Отложить`
- если пользователь молчит, бот **перепингивает через 5 минут**

## Важное ограничение Mini App

У Bot API нет нативного popup date/time picker для бота. Поэтому здесь используется **Mini App** с HTML-полями `date` и `time`. Они открываются внутри Telegram и возвращают выбранные данные обратно в бота.

## Основной UX

### 1. Импорт каталога файлом

1. Напиши `/import`
2. Отправь `.json` документом
3. Бот заменит текущий каталог

Формат файла:

```json
{
  "tasks": [
    {
      "id": "kitchen-floor",
      "title": "Мыть пол на кухне",
      "kind": "RECURRING",
      "schedule": {"unit": "WEEK", "interval": 1},
      "recommendedSlots": 1,
      "note": null
    }
  ]
}
```

### 2. Создание нового дела без JSON

1. Напиши `/new`
2. Бот спросит название
3. Потом предложит тип дела кнопками
4. Для регулярных дел попросит интервал числом
5. Потом сохранит дело в каталог

### 3. Настройка напоминания через Mini App

1. Напиши `/tasks`
2. Открой карточку дела
3. Нажми `🗓 Настроить через Mini App`
4. В Mini App выбери дату и время
5. После `Сохранить` бот пришлёт краткую сводку

Для ежедневных дел можно добавить несколько времён.

## Команды

- `/start` — приветствие и краткое меню
- `/help` — справка
- `/tasks` — каталог дел
- `/task <номер|id>` — карточка одного дела
- `/subs` — мои настройки
- `/who <номер|id>` — кто подписан на дело
- `/new` — создать новое дело диалогом
- `/import` — импорт каталога JSON-файлом
- `/tz` — моя таймзона
- `/tzset Europe/Berlin` — установить таймзону вручную
- `/reload` — перечитать каталог из текущего хранилища (PostgreSQL или JSON)
- `/cancel` — отменить текущий сценарий

## Переменные окружения

Обязательные:

- `BOT_TOKEN` — токен бота
- `APP_BASE_URL` — публичный HTTPS URL приложения, например `https://my-bot.example.com`

Общие:

- `BOT_ZONE` — таймзона по умолчанию, например `Asia/Almaty`
- `APP_PORT` или `PORT` — порт встроенного HTTP-сервера для Mini App, по умолчанию `8080`

### PostgreSQL (обязательно)

Начиная с текущей версии, бот работает только с PostgreSQL (stateless database architecture).
Если `BOT_DB_URL` не задан, приложение завершится с ошибкой при старте.

- `BOT_DB_URL` — JDBC URL, например `jdbc:postgresql://db:5432/reminderbotdb`
- `BOT_DB_USER` — пользователь БД
- `BOT_DB_PASSWORD` — пароль БД
- `BOT_DB_SCHEMA` — схема, по умолчанию `public`

Дополнительные alias-переменные, которые тоже поддерживаются:

- URL: `JDBC_POSTGRES_URI`, `JDBC_DATABASE_URL`
- User: `PGUSER`, `USERNAME`
- Password: `PGPASSWORD`, `PASSWORD`
- Также можно собрать URL из `BOT_DB_HOST` + `BOT_DB_PORT` + `BOT_DB_NAME`

> Примечание: `BOT_STATE_FILE` и `BOT_CATALOG_FILE` больше не используются в рабочем режиме.

## Запуск локально

```bash
BOT_TOKEN=123:abc \
BOT_ZONE=Asia/Almaty \
APP_BASE_URL=https://your-public-url.example.com \
mvn exec:java
```

## Сборка jar

```bash
mvn clean package
java -jar target/reminderbot-7.0.0.jar
```

## Что должно быть на хосте

Чтобы Mini App открывался в Telegram, `APP_BASE_URL` должен указывать на **публичный HTTPS адрес**, который ведёт на этот Java-процесс.

Для PostgreSQL достаточно пробросить боту env с JDBC-подключением — дополнительных файлов для каталога и состояния тогда не нужно.

То есть для VPS обычно нужно:
- поднять бота на `8080`
- пробросить наружу через `nginx`/`caddy`
- выдать HTTPS
- задать `BOT_DB_URL`, `BOT_DB_USER`, `BOT_DB_PASSWORD`

## Структура проекта

- `src/main/java/...` — исходники
- `src/main/resources/db/changelog/...` — миграции схемы PostgreSQL
- `src/main/resources/miniapp/...` — фронтенд Telegram Mini App
