package com.example.reminderbot.storage;

import com.example.reminderbot.dao.*;
import com.example.reminderbot.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatabaseStore implements Store<BotState> {
    private final DataSource dataSource;
    private final UserDao userDao;
    private final TaskDao taskDao;
    private final SubscriptionDao subscriptionDao;
    private final PromptDao promptDao;
    private final CompletionDao completionDao;
    private final SessionDao sessionDao;
    private final AppMetaDao appMetaDao;
    private final ObjectMapper mapper;

    public DatabaseStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.userDao = new UserDao(dataSource);
        this.taskDao = new TaskDao(dataSource);
        this.subscriptionDao = new SubscriptionDao(dataSource);
        this.promptDao = new PromptDao(dataSource);
        this.completionDao = new CompletionDao(dataSource);
        this.sessionDao = new SessionDao(dataSource, mapper);
        this.appMetaDao = new AppMetaDao(dataSource);
    }

    @Override
    public BotState load() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            Map<Long, UserProfile> users = userDao.loadAll(conn);
            List<Subscription> subscriptions = subscriptionDao.loadAll(conn);
            List<ActivePrompt> prompts = promptDao.loadAll(conn);
            Map<Long, UserSession> sessions = sessionDao.loadAll(conn);
            List<CompletionRecord> completions = completionDao.loadAll(conn);
            long lastUpdateId = appMetaDao.getLastUpdateId(conn);
            return new BotState(users, subscriptions, prompts, sessions, completions, lastUpdateId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load state from database", e);
        }
    }

    @Override
    public synchronized void save(BotState state) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (UserProfile user : state.users().values()) {
                    userDao.upsert(conn, user);
                }

                for (Subscription sub : state.subscriptions()) {
                    subscriptionDao.upsert(conn, sub);
                }

                for (ActivePrompt prompt : state.prompts()) {
                    promptDao.upsert(conn, prompt);
                }

                for (Map.Entry<Long, UserSession> entry : state.sessions().entrySet()) {
                    sessionDao.upsert(conn, entry.getKey(), entry.getValue());
                }

                for (CompletionRecord record : state.completions()) {
                    completionDao.insert(conn, record);
                }

                completionDao.cleanupOld(conn, Instant.now().minus(Duration.ofDays(14)));

                appMetaDao.setLastUpdateId(conn, state.lastUpdateId());

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save state to database", e);
        }
    }

    @Override
    public ObjectMapper mapper() {
        return mapper;
    }

    public Catalog loadCatalog() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            List<TaskDefinition> tasks = taskDao.loadAll(conn);
            return new Catalog(tasks);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load catalog from database", e);
        }
    }

    public void saveCatalog(Catalog catalog) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (TaskDefinition task : catalog.tasks()) {
                    taskDao.upsert(conn, task);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save catalog to database", e);
        }
    }
}
