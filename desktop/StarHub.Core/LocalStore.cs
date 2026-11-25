using Microsoft.Data.Sqlite;
using System.Text.Json;

namespace StarHub.Core;

public sealed partial class LocalStore
{
    private readonly string connectionString;
    public string DirectoryPath { get; }

    public LocalStore(string directory)
    {
        DirectoryPath = Path.GetFullPath(directory);
        Directory.CreateDirectory(DirectoryPath);
        connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(DirectoryPath, "starhub.db"),
            ForeignKeys = true,
            DefaultTimeout = 5
        }.ToString();

        using var connection = Open();
        using var journal = Command(connection, "PRAGMA journal_mode = WAL");
        journal.ExecuteScalar();
        using var transaction = connection.BeginTransaction(deferred: false);
        using var schemaVersion = Command(connection, "PRAGMA user_version", transaction);
        long version = (long)schemaVersion.ExecuteScalar()!;
        if (version is < 0 or > 5) throw new StarHubException("This data folder needs a newer version of Star Hub.");
        if (version == 5)
        {
            transaction.Commit();
            return;
        }
        using var schema = Command(connection, """
            CREATE TABLE IF NOT EXISTS preferences (
                id INTEGER PRIMARY KEY CHECK(id = 1), content TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS conversations_updated ON conversations(updated DESC);
            CREATE TABLE IF NOT EXISTS turns (
                id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                created INTEGER NOT NULL, prompt TEXT NOT NULL, reply TEXT NOT NULL DEFAULT '',
                model TEXT NOT NULL, state INTEGER NOT NULL, detail TEXT NOT NULL DEFAULT '',
                sources TEXT NOT NULL DEFAULT '[]', output_tokens INTEGER,
                file_name TEXT, file_type TEXT, file_bytes BLOB
            );
            CREATE INDEX IF NOT EXISTS turns_conversation ON turns(conversation_id, created);
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY, text TEXT NOT NULL UNIQUE, updated INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS schedules (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, prompt TEXT NOT NULL, provider INTEGER NOT NULL,
                web_search INTEGER NOT NULL, interval_minutes INTEGER NOT NULL CHECK(interval_minutes > 0),
                next_run INTEGER NOT NULL, enabled INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
                prompt TEXT NOT NULL, provider INTEGER NOT NULL, web_search INTEGER NOT NULL,
                started INTEGER NOT NULL, state INTEGER NOT NULL,
                result TEXT NOT NULL DEFAULT '', detail TEXT NOT NULL DEFAULT '', sources TEXT NOT NULL DEFAULT '[]'
            );
            CREATE INDEX IF NOT EXISTS runs_schedule ON runs(schedule_id, started DESC);
            """, transaction);
        if (version == 0) schema.ExecuteNonQuery();
        if (version < 2)
        {
            using var projects = Command(connection, ProjectsSchema, transaction);
            projects.ExecuteNonQuery();
        }
        if (version < 3)
        {
            using var devices = Command(connection, DeviceSchema, transaction);
            devices.ExecuteNonQuery();
        }
        if (version < 4)
        {
            using var alerts = Command(connection, DeviceAlertSchema, transaction);
            alerts.ExecuteNonQuery();
        }
        using var generatedPdf = Command(connection, "ALTER TABLE turns ADD COLUMN generated_pdf TEXT; PRAGMA user_version = 5;", transaction);
        generatedPdf.ExecuteNonQuery();
        transaction.Commit();
    }

    public Preferences ReadPreferences()
    {
        using var connection = Open();
        using var command = Command(connection, "SELECT content FROM preferences WHERE id = 1");
        return command.ExecuteScalar() is string json
            ? JsonSerializer.Deserialize<Preferences>(json) ?? new()
            : new();
    }

    public void SavePreferences(Preferences preferences)
    {
        using var connection = Open();
        using var command = Command(connection, """
            INSERT INTO preferences VALUES (1, $content)
            ON CONFLICT(id) DO UPDATE SET content = excluded.content
            """, values: [("$content", JsonSerializer.Serialize(preferences))]);
        command.ExecuteNonQuery();
    }

    public IReadOnlyList<Conversation> Conversations(string filter = "", string? projectId = null)
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT id, title, updated FROM conversations WHERE project_id IS $project
                AND (instr(lower(title), lower($filter)) > 0 OR $filter = '')
                ORDER BY updated DESC, rowid DESC LIMIT 400
            """, values: [("$project", projectId), ("$filter", filter.Trim())]);
        using var rows = command.ExecuteReader();
        var conversations = new List<Conversation>();
        while (rows.Read())
            conversations.Add(new Conversation(rows.GetString(0), rows.GetString(1), rows.GetInt64(2)));
        return conversations;
    }

    public Turn StartTurn(string conversationId, string prompt, string model, Attachment? file, string? projectId = null,
        bool localOnly = false)
    {
        var turnId = Guid.NewGuid().ToString("N");
        var now = Now();
        var title = string.Join(' ', prompt.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        if (title.Length > 68)
        {
            int end = char.IsHighSurrogate(title[67]) ? 67 : 68;
            title = title[..end] + "…";
        }
        using var connection = Open();
        using var transaction = connection.BeginTransaction();
        using var conversation = Command(connection, """
            INSERT INTO conversations(id, title, updated, project_id) VALUES ($conversation, $title, $now, $project)
            ON CONFLICT(id) DO UPDATE SET updated = excluded.updated WHERE conversations.project_id IS excluded.project_id
            """, transaction, ("$conversation", conversationId), ("$title", title), ("$now", now), ("$project", projectId));
        if (conversation.ExecuteNonQuery() != 1) throw new StarHubException("Open this conversation from its own project.");
        using var turn = Command(connection, """
            INSERT INTO turns(id, conversation_id, created, prompt, model, state, file_name, file_type, file_bytes, local_only)
            VALUES ($id, $conversation, $now, $prompt, $model, $running, $name, $type, $bytes, $local)
            """, transaction, ("$id", turnId), ("$conversation", conversationId), ("$now", now),
            ("$prompt", prompt), ("$model", model), ("$running", (int)TurnState.Running),
            ("$name", file?.Name), ("$type", file?.MediaType), ("$bytes", file?.Bytes), ("$local", localOnly));
        turn.ExecuteNonQuery();
        transaction.Commit();
        return new Turn(turnId, prompt, "", model, TurnState.Running, "", [], null, file?.Name, LocalOnly: localOnly);
    }

    public IReadOnlyList<Turn> ReadTurns(string conversationId, int? maxRecent = null)
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT id, prompt, reply, model, state, detail, sources, output_tokens, file_name, project_refs, local_only, generated_pdf
            FROM turns WHERE conversation_id = $id ORDER BY created DESC, rowid DESC LIMIT $limit
            """, values: [("$id", conversationId), ("$limit", maxRecent ?? -1)]);
        using var rows = command.ExecuteReader();
        var turns = new List<Turn>();
        while (rows.Read())
        {
            turns.Add(new Turn(rows.GetString(0), rows.GetString(1), rows.GetString(2), rows.GetString(3),
                (TurnState)rows.GetInt32(4), rows.GetString(5), ReadSources(rows.GetString(6)),
                rows.IsDBNull(7) ? null : rows.GetInt32(7), rows.IsDBNull(8) ? null : rows.GetString(8),
                JsonSerializer.Deserialize<ProjectReference[]>(rows.GetString(9)) ?? [], rows.GetBoolean(10),
                rows.IsDBNull(11) ? null : rows.GetString(11)));
        }
        turns.Reverse();
        return turns;
    }

    public IReadOnlyList<ChatInput> RecentContext(string conversationId, Provider provider, bool includePreviousFile)
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT id, prompt, reply, file_name FROM turns WHERE conversation_id = $id
                AND local_only = 0 AND state IN ($complete, $partial) AND reply <> ''
                ORDER BY created DESC, rowid DESC LIMIT 24
            """, values: [("$id", conversationId), ("$complete", (int)TurnState.Completed),
                ("$partial", (int)TurnState.Partial)]);
        using var rows = command.ExecuteReader();
        var newestFirst = new List<ChatInput>();
        var length = 0;
        string? previousFileTurn = null;
        int previousFileIndex = -1;
        while (rows.Read())
        {
            string user = rows.GetString(1), assistant = rows.GetString(2);
            length += user.Length + assistant.Length;
            if (length > 96_000) break;
            newestFirst.Add(new ChatInput("assistant", assistant));
            if (previousFileTurn is null && !rows.IsDBNull(3) && includePreviousFile)
            {
                previousFileTurn = rows.GetString(0);
                previousFileIndex = newestFirst.Count;
            }
            newestFirst.Add(new ChatInput("user", user));
        }

        rows.Close();
        if (previousFileTurn is not null)
        {
            // Read a file only when needed and send at most one historical attachment.
            using var file = Command(connection, """
                SELECT file_name, file_type, file_bytes FROM turns WHERE id = $id
                """, values: [("$id", previousFileTurn)]);
            using var entry = file.ExecuteReader();
            if (entry.Read())
            {
                string mediaType = entry.GetString(1);
                if (provider == Provider.OpenAI || mediaType != "application/pdf")
                    newestFirst[previousFileIndex] = newestFirst[previousFileIndex] with
                    {
                        File = new Attachment(entry.GetString(0), mediaType, (byte[])entry.GetValue(2))
                    };
            }
        }
        newestFirst.Reverse();
        return newestFirst;
    }

    public void FinishTurn(Turn turn)
    {
        using var connection = Open();
        using var command = Command(connection, """
            UPDATE turns SET reply = $reply, state = $state, detail = $detail, sources = $sources,
                output_tokens = $tokens, project_refs = $projectRefs, generated_pdf = $pdf WHERE id = $id AND state = $running
            """, values: [("$reply", turn.Reply), ("$state", (int)turn.State), ("$detail", turn.Detail),
                ("$sources", JsonSerializer.Serialize(turn.Sources)), ("$tokens", turn.OutputTokens),
                ("$projectRefs", JsonSerializer.Serialize(turn.ProjectSources ?? [])),
                ("$pdf", turn.GeneratedPdf),
                ("$id", turn.Id), ("$running", (int)TurnState.Running)]);
        command.ExecuteNonQuery();
    }

    public void MarkInterruptedChats() => MarkInterrupted("turns");

    public Conversation? FindConversation(string id)
    {
        using var connection = Open();
        using var command = Command(connection, "SELECT title, updated FROM conversations WHERE id = $id", values: [("$id", id)]);
        using var row = command.ExecuteReader();
        return row.Read() ? new Conversation(id, row.GetString(0), row.GetInt64(1)) : null;
    }

    public void RenameConversation(string id, string title)
    {
        title = title.Trim();
        if (title.Length is 0 or > 120 || title.Any(char.IsControl))
            throw new StarHubException("Enter a conversation title of 1–120 characters.");
        using var connection = Open();
        using var command = Command(connection, "UPDATE conversations SET title = $title WHERE id = $id",
            values: [("$id", id), ("$title", title)]);
        command.ExecuteNonQuery();
    }

    public void DeleteConversation(string id)
    {
        using var connection = Open();
        using var command = Command(connection, "DELETE FROM conversations WHERE id = $id", values: [("$id", id)]);
        command.ExecuteNonQuery();
    }

    internal SqliteConnection Open()
    {
        var connection = new SqliteConnection(connectionString);
        connection.Open();
        return connection;
    }

    private static SqliteCommand Command(SqliteConnection connection, string sql,
        SqliteTransaction? transaction = null, params (string Key, object? Value)[] values)
    {
        var command = connection.CreateCommand();
        command.CommandText = sql;
        command.Transaction = transaction;
        foreach (var (key, value) in values) command.Parameters.AddWithValue(key, value ?? DBNull.Value);
        return command;
    }

    private void MarkInterrupted(string table)
    {
        using var connection = Open();
        using var command = Command(connection, $"""
            UPDATE {table} SET state = $interrupted, detail = 'Interrupted before a result was confirmed.'
            WHERE state = $running
            """, values: [("$running", (int)TurnState.Running), ("$interrupted", (int)TurnState.Interrupted)]);
        command.ExecuteNonQuery();
    }

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    private static IReadOnlyList<Source> ReadSources(string json) => JsonSerializer.Deserialize<Source[]>(json) ?? [];
}
