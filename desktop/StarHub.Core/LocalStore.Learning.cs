using System.Text.Json;

namespace StarHub.Core;

public sealed record LearningState(int Next, DateOnly Day, bool Reviewing, string Notes, string Personal, string Draft, string ReviewDraft = "")
{
    public static LearningState Begin(DateOnly today) => new(0, today, false, "", "", "");
    public LearningState OpenOn(DateOnly today) => today > Day
        ? this with { Day = today, Reviewing = Next > 0 || Reviewing } : this;
    public LearningState Record(string note) => this with { Notes = Notes.Length == 0 ? note : Notes + "\n\n" + note };
}

public sealed partial class LocalStore
{
    private const string LearningSchema = """
        CREATE TABLE IF NOT EXISTS workbook_learning (
            chat TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            path TEXT NOT NULL COLLATE NOCASE, revision TEXT NOT NULL, state TEXT NOT NULL, updated INTEGER NOT NULL,
            PRIMARY KEY(chat, path, revision)
        );
        CREATE INDEX IF NOT EXISTS workbook_learning_recent ON workbook_learning(updated DESC);
        PRAGMA user_version = 7;
        """;

    public (string Chat, string Path)? RecentLesson(string? chat, string? project)
    {
        using var connection = Open();
        using var cmd = Command(connection, """
            SELECT l.chat, l.path FROM workbook_learning l JOIN conversations c ON c.id=l.chat
            WHERE ($chat IS NULL OR l.chat=$chat) AND c.project_id IS $project
            ORDER BY l.updated DESC, l.rowid DESC LIMIT 1
            """, values: [("$chat", chat), ("$project", project)]);
        using var row = cmd.ExecuteReader();
        return row.Read() ? (row.GetString(0), row.GetString(1)) : null;
    }

    public LearningState? ReadLearning(string chat, string path, string revision)
    {
        using var connection = Open();
        using var cmd = Command(connection, "SELECT state FROM workbook_learning WHERE chat=$chat AND path=$path AND revision=$rev",
            values: [("$chat", chat), ("$path", path), ("$rev", revision)]);
        return cmd.ExecuteScalar() is string json ? JsonSerializer.Deserialize<LearningState>(json) : null;
    }

    public void SaveLearning(string chat, string path, string revision, LearningState state)
    {
        using var connection = Open();
        using var cmd = Command(connection, """
            INSERT INTO workbook_learning(chat,path,revision,state,updated) VALUES($chat,$path,$rev,$state,$updated)
            ON CONFLICT(chat,path,revision) DO UPDATE SET state=excluded.state, updated=excluded.updated
            """, values: [("$chat", chat), ("$path", path), ("$rev", revision), ("$state", JsonSerializer.Serialize(state)),
                ("$updated", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())]);
        cmd.ExecuteNonQuery();
    }
}
