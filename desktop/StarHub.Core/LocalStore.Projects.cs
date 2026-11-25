using System.Text.RegularExpressions;
using Microsoft.Data.Sqlite;

namespace StarHub.Core;

public sealed record Project(string Id, string Name)
{
    public override string ToString() => Name;
}

public sealed record ProjectDocument(string Id, string Name, int Sections)
{
    public override string ToString() => $"{Name} · {Sections}";
}

public sealed record ProjectReference(string Document, int Section);
public sealed record ProjectExcerpt(ProjectReference Reference, string Text);

public sealed partial class LocalStore
{
    private const string ProjectsSchema = """
        CREATE TABLE projects (
            id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE, created INTEGER NOT NULL
        );
        CREATE TABLE project_documents (
            id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
            name TEXT NOT NULL COLLATE NOCASE, updated INTEGER NOT NULL, UNIQUE(project_id, name)
        );
        CREATE TABLE project_passages (
            id INTEGER PRIMARY KEY, document_id TEXT NOT NULL REFERENCES project_documents(id) ON DELETE CASCADE,
            section INTEGER NOT NULL, content TEXT NOT NULL
        );
        CREATE INDEX project_passages_document ON project_passages(document_id);
        CREATE VIRTUAL TABLE project_search USING fts5(terms, tokenize = 'unicode61');
        CREATE TRIGGER project_passage_deleted AFTER DELETE ON project_passages BEGIN
            DELETE FROM project_search WHERE rowid = OLD.id;
        END;
        ALTER TABLE conversations ADD COLUMN project_id TEXT REFERENCES projects(id) ON DELETE RESTRICT;
        ALTER TABLE turns ADD COLUMN project_refs TEXT NOT NULL DEFAULT '[]';
        CREATE INDEX project_conversations ON conversations(project_id, updated DESC);
        """;

    public IReadOnlyList<Project> Projects()
    {
        using var connection = Open();
        using var command = Command(connection, "SELECT id, name FROM projects ORDER BY name");
        using var rows = command.ExecuteReader();
        var projects = new List<Project>();
        while (rows.Read()) projects.Add(new Project(rows.GetString(0), rows.GetString(1)));
        return projects;
    }

    public Project CreateProject(string name)
    {
        name = CheckProjectName(name);
        var project = new Project(Guid.NewGuid().ToString("N"), name);
        using var connection = Open();
        using var command = Command(connection, "INSERT INTO projects VALUES ($id, $name, $now)",
            values: [("$id", project.Id), ("$name", project.Name), ("$now", Now())]);
        try { command.ExecuteNonQuery(); }
        catch (SqliteException error) when (error.SqliteExtendedErrorCode == 2067)
        { throw new StarHubException("A project with this name already exists."); }
        return project;
    }

    public void RenameProject(string id, string name)
    {
        using var connection = Open();
        using var command = Command(connection, "UPDATE projects SET name = $name WHERE id = $id",
            values: [("$id", id), ("$name", CheckProjectName(name))]);
        try { command.ExecuteNonQuery(); }
        catch (SqliteException error) when (error.SqliteExtendedErrorCode == 2067)
        { throw new StarHubException("A project with this name already exists."); }
    }

    private static string CheckProjectName(string value)
    {
        var name = value.Trim();
        if (name.Length is 0 or > 90 || name.Any(char.IsControl))
            throw new StarHubException("Enter a project name of up to 90 characters.");
        return name;
    }

    public IReadOnlyList<ProjectDocument> ProjectDocuments(string projectId)
    {
        using var connection = Open();
        using var command = Command(connection, """
            SELECT d.id, d.name, COUNT(p.id)
            FROM project_documents AS d JOIN project_passages AS p ON p.document_id = d.id
            WHERE d.project_id = $project GROUP BY d.id ORDER BY d.name
            """, values: [("$project", projectId)]);
        using var rows = command.ExecuteReader();
        var documents = new List<ProjectDocument>();
        while (rows.Read()) documents.Add(new ProjectDocument(rows.GetString(0), rows.GetString(1), rows.GetInt32(2)));
        return documents;
    }

    public ProjectDocument SaveProjectDocument(string projectId, string name, string text)
    {
        name = Path.GetFileName(name).Trim();
        text = text.Replace("\r\n", "\n").Trim();
        if (name.Length is 0 or > 250 || name.Any(char.IsControl) || text.Length is 0 or > KnowledgeDocument.MaxTextLength)
            throw new StarHubException("Choose a named document with extractable text within the import limit.");

        var passages = KnowledgeDocument.Split(text).ToArray();
        using var connection = Open();
        using var transaction = connection.BeginTransaction(deferred: false);
        using var write = Command(connection, """
            INSERT INTO project_documents (id, project_id, name, updated) VALUES ($id, $project, $name, $now)
            ON CONFLICT(project_id, name) DO UPDATE SET updated = excluded.updated, name = excluded.name
            RETURNING id
            """, transaction, ("$id", Guid.NewGuid().ToString("N")), ("$project", projectId), ("$name", name), ("$now", Now()));
        var id = (string)write.ExecuteScalar()!;
        using var remove = Command(connection, "DELETE FROM project_passages WHERE document_id = $document",
            transaction, ("$document", id));
        remove.ExecuteNonQuery();

        for (int index = 0; index < passages.Length; index++)
        {
            using var insert = Command(connection, """
                INSERT INTO project_passages (document_id, section, content) VALUES ($document, $section, $content)
                RETURNING id
                """, transaction, ("$document", id), ("$section", index + 1), ("$content", passages[index]));
            long passageId = (long)insert.ExecuteScalar()!;
            using var search = Command(connection, "INSERT INTO project_search(rowid, terms) VALUES ($id, $terms)",
                transaction, ("$id", passageId), ("$terms", string.Join(' ', KnowledgeDocument.Terms(name + " " + passages[index]))));
            search.ExecuteNonQuery();
        }
        transaction.Commit();
        return new ProjectDocument(id, name, passages.Length);
    }

    public void DeleteProjectDocument(string projectId, string documentId)
    {
        using var connection = Open();
        using var command = Command(connection, "DELETE FROM project_documents WHERE project_id = $project AND id = $id",
            values: [("$project", projectId), ("$id", documentId)]);
        command.ExecuteNonQuery();
    }

    public IReadOnlyList<ProjectExcerpt> SearchProject(string projectId, string query)
    {
        // Tokens are extracted locally. Quoting each term gives a literal FTS expression.
        string search = string.Join(" OR ", KnowledgeDocument.Terms(query[..Math.Min(query.Length, 4000)])
            .Distinct().Take(48).Select(word => "\"" + word + "\""));
        if (search.Length == 0) return [];
        using var connection = Open();
        using var command = Command(connection, """
            SELECT d.name, p.section, p.content
            FROM project_search JOIN project_passages AS p ON p.id = project_search.rowid
            JOIN project_documents AS d ON d.id = p.document_id
            WHERE project_search MATCH $query AND d.project_id = $project
            ORDER BY bm25(project_search), p.id LIMIT 6
            """, values: [("$query", search), ("$project", projectId)]);
        using var rows = command.ExecuteReader();
        var excerpts = new List<ProjectExcerpt>();
        while (rows.Read())
            excerpts.Add(new ProjectExcerpt(new ProjectReference(rows.GetString(0), rows.GetInt32(1)), rows.GetString(2)));
        return excerpts;
    }
}
