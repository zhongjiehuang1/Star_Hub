using System.Text.Json;

namespace StarHub.Core;

public sealed partial class LocalStore
{
    public bool HasRunningTasks()
    {
        using var connection = Open();
        using var command = Command(connection, "SELECT EXISTS(SELECT 1 FROM runs WHERE state = $running)",
            values: [("$running", (int)TurnState.Running)]);
        return command.ExecuteScalar() is 1L;
    }

    public IReadOnlyList<TaskSchedule> Schedules()
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT id, name, prompt, provider, web_search, interval_minutes, next_run, enabled
                FROM schedules ORDER BY enabled DESC, next_run
            """);
        using var rows = command.ExecuteReader();
        var list = new List<TaskSchedule>();
        while (rows.Read())
            list.Add(new TaskSchedule(rows.GetString(0), rows.GetString(1), rows.GetString(2),
                (Provider)rows.GetInt32(3), rows.GetBoolean(4), rows.GetInt32(5), rows.GetInt64(6), rows.GetBoolean(7)));
        return list;
    }

    public void AddSchedule(TaskSchedule schedule)
    {
        if (schedule.Name.Trim().Length is 0 or > 80 || schedule.Prompt.Trim().Length is 0 or > 30_000
            || schedule.IntervalMinutes is < 15 or > 43_200 || !Enum.IsDefined(schedule.Provider)
            || schedule.Provider == Provider.Automatic)
            throw new StarHubException("Enter a name, a prompt and an interval from 15 minutes to 30 days. Choose an execution provider.");

        using var connection = Open();
        using var command = Command(connection, """
            INSERT INTO schedules VALUES ($id, $name, $prompt, $provider, $web, $interval, $next, $enabled)
            """, values: [("$id", schedule.Id), ("$name", schedule.Name.Trim()), ("$prompt", schedule.Prompt.Trim()),
                ("$provider", (int)schedule.Provider), ("$web", schedule.WebSearch), ("$interval", schedule.IntervalMinutes),
                ("$next", schedule.NextRun), ("$enabled", schedule.Enabled)]);
        command.ExecuteNonQuery();
    }

    public void SetScheduleEnabled(string id, bool enabled)
    {
        using var connection = Open();
        using var command = Command(connection, """
            UPDATE schedules SET enabled = $enabled,
                next_run = CASE WHEN $enabled AND next_run < $now THEN $now ELSE next_run END WHERE id = $id
            """, values: [("$id", id), ("$enabled", enabled), ("$now", Now())]);
        command.ExecuteNonQuery();
    }

    public bool RemoveSchedule(string id)
    {
        using var connection = Open();
        using var command = Command(connection, """
            DELETE FROM schedules WHERE id = $id AND NOT EXISTS
                (SELECT 1 FROM runs WHERE schedule_id = $id AND state = $running)
            """, values: [("$id", id), ("$running", (int)TurnState.Running)]);
        return command.ExecuteNonQuery() == 1;
    }

    public TaskRun? ClaimDueTask(DateTimeOffset time)
    {
        var now = time.ToUnixTimeMilliseconds();
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using var due = Command(connection, """
            SELECT id, prompt, provider, web_search, next_run, interval_minutes FROM schedules
                WHERE enabled = 1 AND next_run <= $now AND NOT EXISTS
                    (SELECT 1 FROM runs WHERE schedule_id = schedules.id AND state = $running)
                ORDER BY next_run LIMIT 1
            """, transaction, ("$now", now), ("$running", (int)TurnState.Running));
        using var row = due.ExecuteReader();
        if (!row.Read()) return null;
        var run = new TaskRun(Guid.NewGuid().ToString("N"), row.GetString(0), row.GetString(1),
            (Provider)row.GetInt32(2), row.GetBoolean(3), now, TurnState.Running, "", "", []);
        long previous = row.GetInt64(4), interval = row.GetInt64(5) * 60_000;
        long next = previous + ((now - previous) / interval + 1) * interval;
        row.Close();

        using var advance = Command(connection, "UPDATE schedules SET next_run = $next WHERE id = $id",
            transaction, ("$next", next), ("$id", run.ScheduleId));
        advance.ExecuteNonQuery();
        using var claim = Command(connection, """
            INSERT INTO runs (id, schedule_id, prompt, provider, web_search, started, state)
                VALUES ($id, $schedule, $prompt, $provider, $web, $started, $state)
            """, transaction, ("$id", run.Id), ("$schedule", run.ScheduleId), ("$prompt", run.Prompt),
            ("$provider", (int)run.Provider), ("$web", run.WebSearch), ("$started", now), ("$state", (int)run.State));
        claim.ExecuteNonQuery();
        transaction.Commit();
        return run;
    }

    public IReadOnlyList<TaskRun> TaskRuns(string scheduleId)
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT id, prompt, provider, web_search, started, state, result, detail, sources
                FROM runs WHERE schedule_id = $id ORDER BY started DESC LIMIT 50
            """, values: [("$id", scheduleId)]);
        using var rows = command.ExecuteReader();
        var runs = new List<TaskRun>();
        while (rows.Read())
            runs.Add(new TaskRun(rows.GetString(0), scheduleId, rows.GetString(1), (Provider)rows.GetInt32(2),
                rows.GetBoolean(3), rows.GetInt64(4), (TurnState)rows.GetInt32(5), rows.GetString(6), rows.GetString(7),
                ReadSources(rows.GetString(8))));
        return runs;
    }

    public void FinishTask(TaskRun run)
    {
        using var connection = Open();
        using var command = Command(connection, """
            UPDATE runs SET state = $state, result = $result, detail = $detail, sources = $sources
                WHERE id = $id AND state = $running
            """, values: [("$state", (int)run.State), ("$result", run.Result), ("$detail", run.Detail),
                ("$sources", JsonSerializer.Serialize(run.Sources)), ("$id", run.Id), ("$running", (int)TurnState.Running)]);
        command.ExecuteNonQuery();
    }

    public void MarkInterruptedTasks() => MarkInterrupted("runs");

    public IReadOnlyList<MemoryNote> Memories(string search = "")
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT id, text, updated FROM memories WHERE $search = '' OR instr(lower(text), lower($search)) > 0
                ORDER BY updated DESC LIMIT 500
            """, values: [("$search", search.Trim())]);
        using var rows = command.ExecuteReader();
        var notes = new List<MemoryNote>();
        while (rows.Read()) notes.Add(new MemoryNote(rows.GetString(0), rows.GetString(1), rows.GetInt64(2)));
        return notes;
    }

    public void SaveMemory(string text, string? existingId = null)
    {
        text = text.Trim();
        if (text.Length is 0 or > 1000) throw new StarHubException("Enter a memory of up to 1,000 characters.");
        using var connection = Open();
        using var command = Command(connection, existingId is null
            ? """
              INSERT INTO memories VALUES ($id, $text, $now) ON CONFLICT(text) DO UPDATE SET updated = excluded.updated
              """
            : "UPDATE memories SET text = $text, updated = $now WHERE id = $id",
            values: [("$id", existingId ?? Guid.NewGuid().ToString("N")), ("$text", text), ("$now", Now())]);
        try { command.ExecuteNonQuery(); }
        catch (Microsoft.Data.Sqlite.SqliteException error) when (error.SqliteErrorCode == 19)
        {
            throw new StarHubException("This memory already exists.");
        }
    }

    public void RemoveMemory(string id)
    {
        using var connection = Open();
        using var command = Command(connection, "DELETE FROM memories WHERE id = $id", values: [("$id", id)]);
        command.ExecuteNonQuery();
    }
}
