package com.example.reminderbot.storage;

import com.example.reminderbot.dao.*;
import com.example.reminderbot.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseStore {
    private static final Logger log = LoggerFactory.getLogger(DatabaseStore.class);
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
        this.userDao = new UserDao();
        this.taskDao = new TaskDao();
        this.subscriptionDao = new SubscriptionDao();
        this.promptDao = new PromptDao();
        this.completionDao = new CompletionDao();
        this.sessionDao = new SessionDao(mapper);
        this.appMetaDao = new AppMetaDao();
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public UserDao users() { return userDao; }
    public TaskDao tasks() { return taskDao; }
    public SubscriptionDao subscriptions() { return subscriptionDao; }
    public PromptDao prompts() { return promptDao; }
    public CompletionDao completions() { return completionDao; }
    public SessionDao sessions() { return sessionDao; }
    public AppMetaDao appMeta() { return appMetaDao; }
    public ObjectMapper mapper() { return mapper; }

    public void saveCompletion(CompletionRecord completion) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            completionDao.upsert(conn, completion);
        } catch (SQLException e) {
            log.error("Failed to save completion record: {}", e.getMessage(), e);
        }
    }
}
