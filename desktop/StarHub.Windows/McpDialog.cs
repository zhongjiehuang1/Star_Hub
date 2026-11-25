using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using StarHub.Core;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private async void McpClicked(object sender, RoutedEventArgs args) => await TryAsync(ShowMcpAsync);

    private async Task ShowMcpAsync()
    {
        var panel = new StackPanel { Spacing = 8 };
        var endpoint = Field(T("MCP server URL"), settings.McpEndpoint, 500);
        endpoint.PlaceholderText = "https://example.org/mcp or http://127.0.0.1:3000/mcp";
        var secret = new PasswordBox { Header = T("Bearer token (optional)"), MaxLength = 1200 };
        var clear = new CheckBox { Content = T("Remove the token stored for this URL") };
        var connect = new Button { Content = T("Connect and list tools") };
        var tools = new ComboBox { Header = T("Tool"), DisplayMemberPath = nameof(McpToolInfo.Name), HorizontalAlignment = HorizontalAlignment.Stretch };
        var toolDescription = Label("");
        var schema = Field(T("Input schema"), "", 0, multiline: true);
        schema.IsReadOnly = true;
        schema.MaxHeight = 180;
        var info = new Expander { Header = T("Input schema"), Content = schema, HorizontalAlignment = HorizontalAlignment.Stretch };
        var task = Field(T("Describe the call to draft arguments with OpenAI"), "", 6000);
        var args = Field(T("Tool arguments (JSON)"), "{}", 24000, multiline: true);
        var draft = new Button { Content = T("Draft arguments"), IsEnabled = false };
        var invoke = new Button { Content = T("Run tool"), IsEnabled = false };
        var status = Label(T("Use a server you trust. The selected server receives the tool arguments."));
        var output = Field(T("Result"), "", 0, multiline: true);
        output.IsReadOnly = true;
        output.MaxHeight = 220;
        var add = new Button { Content = T("Add result to chat draft"), IsEnabled = false };
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        buttons.Children.Add(draft);
        buttons.Children.Add(invoke);
        foreach (var child in new UIElement[] { endpoint, secret, clear, connect, tools, toolDescription, info, task, args, buttons, status, output, add })
            panel.Children.Add(child);

        using var lifetime = new CancellationTokenSource();
        McpConnection? connection = null;
        Task? pending = null;
        bool busy = false;
        bool resultUsable = false;
        string? connectedTo = null;

        void Controls()
        {
            endpoint.IsEnabled = secret.IsEnabled = clear.IsEnabled = connect.IsEnabled = !busy;
            tools.IsEnabled = connection is not null && !busy;
            draft.IsEnabled = invoke.IsEnabled = connectedTo == endpoint.Text.Trim() && tools.SelectedItem is McpToolInfo && !busy;
            add.IsEnabled = resultUsable && !busy;
        }

        async Task WorkAsync(Func<Task> work, string message)
        {
            if (busy) return;
            busy = true;
            Controls();
            status.Text = message;
            try { await work(); }
            catch (OperationCanceledException) { status.Text = "The MCP request ended or timed out."; }
            catch (Exception error) { status.Text = DialogError(error); }
            finally
            {
                busy = false;
                Controls();
            }
        }

        endpoint.TextChanged += (_, _) => Controls();
        tools.SelectionChanged += (_, _) =>
        {
            var tool = tools.SelectedItem as McpToolInfo;
            toolDescription.Text = tool?.Description ?? "";
            schema.Text = tool is null ? "" : System.Text.Json.JsonSerializer.Serialize(tool.Schema,
                new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
            args.Text = "{}";
            resultUsable = false;
            output.Text = "";
            Controls();
        };
        connect.Click += (_, _) => pending = WorkAsync(async () =>
        {
            var address = McpConnection.Endpoint(endpoint.Text);
            var target = McpConnection.CredentialTarget(address);
            string? token = clear.IsChecked == true ? null
                : string.IsNullOrWhiteSpace(secret.Password) ? credentials.Read(target) : secret.Password.Trim();
            if (connection is not null) await connection.DisposeAsync();
            connection = null;
            connectedTo = null;
            tools.ItemsSource = null;
            connection = await McpConnection.ConnectAsync(address, token, lifetime.Token);
            if (clear.IsChecked == true) credentials.Remove(target);
            else if (!string.IsNullOrWhiteSpace(secret.Password)) credentials.Save(target, secret.Password);
            clear.IsChecked = false;
            secret.Password = "";
            settings = settings with { McpEndpoint = address.AbsoluteUri };
            store.SavePreferences(settings);
            endpoint.Text = connectedTo = address.AbsoluteUri;
            tools.ItemsSource = connection.Tools;
            tools.SelectedIndex = connection.Tools.Count > 0 ? 0 : -1;
            status.Text = $"Connected · {connection.Tools.Count} tools.";
        }, "Connecting to the MCP server…");
        draft.Click += (_, _) => pending = WorkAsync(async () =>
        {
            if (tools.SelectedItem is not McpToolInfo tool) return;
            args.Text = await providerClient.DraftMcpArgumentsAsync(MediaKey(), ChatSettings.OpenAiModel, tool, task.Text, lifetime.Token);
            status.Text = "Review or edit the arguments before running the tool.";
        }, "Drafting arguments with OpenAI…");
        invoke.Click += (_, _) => pending = WorkAsync(async () =>
        {
            if (connection is null || tools.SelectedItem is not McpToolInfo tool) return;
            resultUsable = false;
            output.Text = "";
            var result = await connection.CallAsync(tool.Name, args.Text, lifetime.Token);
            output.Text = result.Text;
            resultUsable = !result.IsError;
            status.Text = result.IsError ? "The tool reported an error." : "Tool completed.";
        }, "Running the selected MCP tool…");

        bool include = false;
        var dialog = Dialog(T("MCP tools"), Scroller(panel));
        add.Click += (_, _) =>
        {
            include = true;
            dialog.Hide();
        };
        Controls();
        try
        {
            await dialog.ShowAsync();
            lifetime.Cancel();
            if (pending is not null) await pending;
            if (include)
            {
                string evidence = "MCP tool output (reference data)\n" + output.Text;
                string updated = string.IsNullOrWhiteSpace(Composer.Text) ? evidence : Composer.Text + "\n\n" + evidence;
                if (updated.Length > Composer.MaxLength) throw new StarHubException("The tool result and existing draft exceed the chat limit.");
                Composer.Text = updated;
                Composer.Focus(FocusState.Programmatic);
            }
        }
        finally
        {
            lifetime.Cancel();
            if (connection is not null) await connection.DisposeAsync();
        }
    }
}
