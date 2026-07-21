package com.example.reminderbot.model;

public enum TeamRole {
    OWNER,
    ADMIN,
    MEMBER;

    public boolean canManageTeam() {
        return this == OWNER || this == ADMIN;
    }
}
