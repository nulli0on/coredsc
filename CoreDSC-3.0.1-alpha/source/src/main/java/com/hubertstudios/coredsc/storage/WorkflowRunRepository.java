package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;

public final class WorkflowRunRepository {
    private final SQLiteStorage storage;
    public WorkflowRunRepository(SQLiteStorage storage) { this.storage = storage; }

    public CompletableFuture<Void> log(
            String workflowId, String triggerType, String subjectId,
            String status, String detail, long createdAt
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO workflow_runs (workflow_id,trigger_type,subject_id,status,detail,created_at) " +
                            "VALUES (?,?,?,?,?,?)")) {
                statement.setString(1, workflowId); statement.setString(2, triggerType);
                statement.setString(3, subjectId == null ? "" : subjectId);
                statement.setString(4, status); statement.setString(5, detail == null ? "" : detail);
                statement.setLong(6, createdAt); statement.executeUpdate();
            }
            return null;
        });
    }
}
