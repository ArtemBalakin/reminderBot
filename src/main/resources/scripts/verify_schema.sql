-- Быстрая проверка после liquibase update
\dt
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;
SELECT * FROM app_meta;
