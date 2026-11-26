using System.Text.Json;

namespace StarHub.Core;

public sealed partial class LocalStore
{
    private const string LocalWorkSchema = """
        CREATE TABLE IF NOT EXISTS local_work (
            turn_id TEXT PRIMARY KEY REFERENCES turns(id) ON DELETE CASCADE, operation_id TEXT NOT NULL,
            kind INTEGER NOT NULL, request TEXT NOT NULL, receipt TEXT
        );
        CREATE INDEX IF NOT EXISTS local_work_operation ON local_work(operation_id);
        CREATE TABLE IF NOT EXISTS local_lessons (
            conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
            complete INTEGER NOT NULL CHECK(complete IN (0, 1))
        );
        PRAGMA user_version = 6;
        """;

    public WorkCheckpoint SaveWork(string turnId, string conversation, WorkRequest request, string? operationId = null)
    {
        operationId ??= turnId;
        using var connection = Open();
        using var command = Command(connection, """
            INSERT INTO local_work(turn_id, operation_id, kind, request)
            VALUES($turn, $operation, $kind, $request)
            """, values: [("$turn", turnId), ("$operation", operationId), ("$kind", (int)request.Kind),
                ("$request", JsonSerializer.Serialize(request))]);
        command.ExecuteNonQuery();
        return new(operationId, conversation, request, null);
    }

    public void CompleteWork(string operationId, WorkReceipt receipt)
    {
        using var connection = Open();
        using var command = Command(connection, """
            UPDATE local_work SET receipt = $receipt WHERE operation_id = $operation AND receipt IS NULL
            """, values: [("$operation", operationId), ("$receipt", JsonSerializer.Serialize(receipt))]);
        command.ExecuteNonQuery();
    }

    public WorkCheckpoint? LatestWork(string? conversation, string? project, bool unfinished)
    {
        using var connection = Open();
        string filter = unfinished ? "work.receipt IS NULL AND t.state IN ($cancelled,$interrupted)"
            : "work.receipt IS NOT NULL AND work.kind IN ($workbook,$summary)";
        using var command = Command(connection, $"""
            SELECT work.operation_id, t.conversation_id, work.request, work.receipt
            FROM local_work work JOIN turns t ON work.turn_id = t.id JOIN conversations c ON c.id = t.conversation_id
            WHERE c.project_id IS $project AND ($chat IS NULL OR t.conversation_id = $chat) AND {filter}
            ORDER BY t.created DESC, t.rowid DESC LIMIT 1
            """, values: [("$project", project), ("$chat", conversation), ("$workbook", (int)WorkKind.CostWorkbook),
                ("$summary", (int)WorkKind.ProductSummary), ("$cancelled", (int)TurnState.Cancelled),
                ("$interrupted", (int)TurnState.Interrupted)]);
        using var row = command.ExecuteReader();
        return row.Read() ? new(row.GetString(0), row.GetString(1), JsonSerializer.Deserialize<WorkRequest>(row.GetString(2))!,
            row.IsDBNull(3) ? null : JsonSerializer.Deserialize<WorkReceipt>(row.GetString(3))) : null;
    }

    public bool LessonComplete(string conversation)
    {
        using var connection = Open();
        using var command = Command(connection, "SELECT complete FROM local_lessons WHERE conversation_id = $chat",
            values: [("$chat", conversation)]);
        return command.ExecuteScalar() is 1L;
    }

    public void SaveLessonCompletion(string conversation)
    {
        using var connection = Open();
        using var command = Command(connection, """
            INSERT INTO local_lessons(conversation_id, complete) VALUES ($chat, 1)
            ON CONFLICT(conversation_id) DO UPDATE SET complete = 1
            """, values: [("$chat", conversation)]);
        command.ExecuteNonQuery();
    }
}
