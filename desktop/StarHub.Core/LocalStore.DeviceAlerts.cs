namespace StarHub.Core;

public sealed record DeviceAlert(DateTimeOffset CreatedAt, string Title, string Message, string Report);

public sealed partial class LocalStore
{
    private const string DeviceAlertSchema = """
        CREATE TABLE device_alerts (
            created INTEGER PRIMARY KEY, title TEXT NOT NULL, message TEXT NOT NULL, report TEXT NOT NULL
        );
        CREATE TABLE device_alert_state (
            id INTEGER PRIMARY KEY CHECK(id = 1), notified INTEGER NOT NULL, last_alert INTEGER
        );
        INSERT INTO device_alert_state VALUES (1, 0, NULL);
        """;

    private static readonly TimeSpan DeviceAlertCooldown = TimeSpan.FromMinutes(30);

    public DeviceAlert? LatestDeviceAlert()
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT created, title, message, report FROM device_alerts ORDER BY created DESC LIMIT 1
            """);
        using var row = command.ExecuteReader();
        return row.Read() ? new DeviceAlert(DateTimeOffset.FromUnixTimeMilliseconds(row.GetInt64(0)),
            row.GetString(1), row.GetString(2), row.GetString(3)) : null;
    }

    internal DeviceAlert? UpdateDeviceAlert(DateTimeOffset now, DevicePressure active, DevicePressure recovered,
        Func<DeviceAlert> createReport)
    {
        if (active == DevicePressure.None && recovered == DevicePressure.None) return null;
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using var query = Command(connection, "SELECT notified, last_alert FROM device_alert_state WHERE id = 1", transaction);
        using var row = query.ExecuteReader();
        row.Read();
        var notified = (DevicePressure)row.GetInt32(0);
        long? last = row.IsDBNull(1) ? null : row.GetInt64(1);
        row.Close();

        var ongoing = notified & ~recovered;
        long timestamp = now.ToUnixTimeMilliseconds();
        DeviceAlert? alert = null;
        if ((active & ~ongoing) != DevicePressure.None &&
            (!last.HasValue || timestamp - last.Value >= DeviceAlertCooldown.TotalMilliseconds))
        {
            alert = createReport();
            using var insert = Command(connection, """
                INSERT INTO device_alerts(created, title, message, report) VALUES ($created, $title, $message, $report)
                """, transaction, ("$created", timestamp), ("$title", alert.Title), ("$message", alert.Message), ("$report", alert.Report));
            insert.ExecuteNonQuery();
            ongoing |= active;
            last = timestamp;
        }

        if (alert is not null || ongoing != notified)
        {
            using var update = Command(connection, "UPDATE device_alert_state SET notified = $notified, last_alert = $last WHERE id = 1",
                transaction, ("$notified", (int)ongoing), ("$last", last));
            update.ExecuteNonQuery();
        }
        transaction.Commit();
        return alert;
    }
}
