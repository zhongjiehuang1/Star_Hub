# Star Hub for Windows · First preview

Star Hub Desktop runs in a native WinUI 3 window on Windows 10 version 22H2 (build 19045) or Windows 11, on x64 PCs. Its separate worker can run recurring AI tasks while the Windows account is signed in and the computer is awake.

[Download the first Windows preview](https://starhub.zhongjiehuang.com/downloads/)

## Use the app

1. Select the desktop download icon in the web workspace and confirm Windows. Open the downloaded setup file. It installs Star Hub for the current Windows user and starts the app when it finishes.
2. Select **Sign in to Star Hub** in the sidebar and approve the connection in your browser. The app displays the name saved to your account and uses your website chat model and active OpenAI key. Select your name and choose **Refresh from website** to load changes or refresh an older connection. Desktop conversations and files stay on this PC. Manual model connections are under **Settings → Advanced tools → Model and reasoning → Model connections**.
3. Type a message or click the microphone to speak. The waveform follows the microphone level and live transcription previews the words as you speak. Click the microphone again to finish and send. If you already have a draft, it adds the words to that draft. Voice transcription uses the connected OpenAI key. **Settings → Advanced tools → Model and reasoning** contains model controls. Enter sends and Shift+Enter inserts a line break.
4. Use the file picker to attach UTF-8 text, CSV, JSON, PDF, PNG, JPEG or WebP. Text files support up to 96 KB and PDFs and images up to 6 MiB. PDF analysis uses OpenAI. Image input on other providers requires a vision model.
5. Open the **All-purpose chat** menu for image generation and editing, audio-file transcription and live voice. The selected audio or image input goes to OpenAI. Use Read aloud on a chat reply to hear it through an installed Windows voice.
6. Open Scheduled tasks to save a recurring prompt, check past runs, or start the worker at sign-in. A recurring task uses the model configured for its chosen connection. Change the default cloud model in Settings to change the model used by future OpenAI runs.
7. Select **Device** in the app header to see 5- or 30-minute trends for CPU, memory, GPU and disks. Expand a category for network and power trends, capacity bars or a process ranking. The background worker collects readings about every two seconds while the Windows session and worker are running. Expand **Hardware and firmware** for device inventory. Windows and hardware drivers determine which measurements are available.
8. Ask chat “How is my PC running?”, “Why is my computer slow?” or “最近半小时电脑为什么变慢？”. Star Hub compares live readings with recent history and an earlier baseline when enough samples exist, then offers steps for the observed pressure. The composer shows **Local device analysis** for these questions. Local analysis needs no provider key.

OpenAI web search returns linked sources. Hosted data analysis uses OpenAI's Code Interpreter tool. The desktop streams directly from OpenAI, Ollama at loopback, or the configured compatible API. In Settings, the OpenAI list includes GPT-6.1 Terra and GPT-6 Luna alongside GPT-6.2 Sol and Astra. Turn on **Search the web** next to the attachment button for a current question.

Open **Settings → Language / 界面语言** to switch between English, Simplified Chinese and the Windows language. To update, finish active work, close Star Hub and run the newer setup from the download page. Setup replaces the installed app and preserves its local data. The Device panel shows five-minute trends for CPU, memory, disk activity and GPU usage when available.

**Operate this PC** starts enabled and remembers your choice. Enter a request such as `打开终端` or `读取 "C:\Users\YourName\Documents\账单.txt"`. Include a destination to copy a file or save a PDF. Use a full local path or a folder name such as Desktop, Documents or Downloads. Star Hub runs supported local actions with your Windows account. Switch off **Operate this PC** to return to ordinary chat.

Complex tasks use the account's OpenAI connection, with Luna or Terra selected automatically. Star Hub shows the plan and the verified results in the conversation. It can read local text and searchable PDFs, create and update text files, copy and move files, open installed apps and use supported Windows accessibility controls. Relevant tool results go to the model for planning and the task record stays on the PC. Clearing the DNS cache and checking the Windows image use Windows UAC.

Ask for a PDF or say **把刚才的回复保存成 PDF**. Use a directory or full PDF filename in the request when computer operation is on. The default location is Desktop. With computer operation off, use **Save PDF** on the generated file card to choose a location.

The main window and background worker start at Windows sign-in. Closing with active work or an unsent draft prompts for confirmation.

## Local data

Star Hub stores chats, attachments, memories, preferences and task runs in SQLite under `%LOCALAPPDATA%\StarHub`. The app stores API keys in Windows Credential Manager and scopes each compatible API key to its configured HTTPS base. Uninstalling removes the application and stops the worker. User data and saved credentials are retained.

Device history uses the same local SQLite database. The worker starts in the background at Windows sign-in and when the installed app opens. It keeps collecting after the panel and main window close, and keeps one historical sample per 15-second interval for seven days. The sign-in option in Scheduled tasks controls future logins. Device reports saved in chat are excluded from later provider requests.

Star Hub sends a Windows notification for sustained CPU, memory, disk, GPU or reported thermal pressure. It checks a two-minute window and compares it with up to two hours of earlier local readings when enough history exists. It waits for recovery before repeating the same condition and sends at most one notification per 30 minutes. Open **Device → Latest performance alert** or ask “What was the latest performance alert?” for the evidence and steps. Alert records are kept locally for seven days, including when Windows notifications are disabled.

A cloud request can include recent conversation messages, a relevant local memory if enabled, and the selected or most recent supported attachment in that context. Select a local Ollama model to keep the chat on the device. OpenAI requests use `store: false`. Provider data handling remains subject to the provider's policy.

The worker runs up to two tasks at a time. One overdue run is eligible when the computer resumes. Closing the window leaves the worker running. Scheduled tasks do not start before Windows sign-in. Windows notifications surface completed jobs when notifications are enabled. Windows Task Manager can stop the worker. Interrupted requests are recorded without automatically replaying the provider call.

## Build and distribution

On an x64 Windows machine, install Git, PowerShell 7.4 or later, Inno Setup 6 and the .NET 10 SDK pinned in `global.json`. NuGet restores the Windows SDK build tools. Run these commands from this directory.

```powershell
dotnet test StarHub.Tests/StarHub.Tests.csproj -c Release
./build-windows.ps1
```

The build produces `out/app/StarHub.exe` and a self-contained setup, SHA-256 checksum and package manifest in `out/release/`. With Python 3 on PATH, run `./verify-windows.ps1` in a disposable Windows profile to test automatic installation and startup, live device readings, local chat, background execution and uninstall. To also exercise the SQLite upgrade from the earlier test package, pass its local installer path with `-PreviousInstaller`. The GitHub workflow is an optional manual runner for build and installation checks.

Attach the verified setup, checksum and manifest to both web origins using [publish-windows-download.py](../deploy/publish-windows-download.py). The publisher archives the package outside the application directory and attaches it at `/downloads/windows/preview/`. To replace the package, supply its current SHA-256 with `--expect-preview-sha256`. After verifying the assets on both origins, supply `--page` and `--expect-page-sha256` to update the download page. After a WAR replacement, run the publisher again with `--assets /var/lib/codex-validation/starhub-windows-releases/<installer-build>` to reattach the preview. The published SHA-256 checksum identifies the installer.

For a signed website build, use Windows SDK SignTool with a publicly trusted code-signing certificate in the build user's `CurrentUser\My` store and its accessible signing key. Run `./build-windows.ps1 -SigningThumbprint <thumbprint> -TimestampUrl <RFC3161-HTTPS-URL> -SignToolPath <signtool.exe-path>`. This signs the Star Hub executables and libraries and lets Inno Setup sign the installer and uninstaller. The build verifies the installer and payload before writing the checksum. Run `./verify-windows.ps1` to check the installed uninstaller. Use a new internal installer build number when replacing an archived preview, then update the download page's signature notice after verifying the package on both origins.

## Features in the preview

The preview includes streaming chat, attachments, Markdown and tables, sources, image generation and editing, recorded speech transcription, local read-aloud, device status, local memory and recurring tasks. The app uses plain text for formulas.

- **Live voice.** All-purpose chat → Live voice connects the selected Windows microphone to OpenAI Realtime. Speak while Star Hub replies to interrupt it, or use Mute and Disconnect. Save a transcript when you want a local copy. Windows audio device settings apply to the microphone and default output.
- **Projects.** Use the folder button in the sidebar to create a project and import UTF-8 documents or PDFs with a text layer. Star Hub indexes files locally, keeps each project's conversations separate, and shows the source sections used in a reply.
Before publishing, verify the Windows install and the live OpenAI checks. On Windows, set `STARHUB_OPENAI_TEST_KEY` for the test process and run `dotnet run --project StarHub.LiveChecks -c Release -f net10.0-windows10.0.22000.0`. The live check uses synthetic PCM for audio, a new disposable Notepad window, and the consented Windows maintenance operations on the test machine. The unit tests also exercise a real loopback MCP server. For the optional manual CI run with live checks, provide the test key to the restricted `starhub-windows-live` environment and allow the intended ref.

To verify Chinese task planning, file operations and Windows authorization on an interactive Windows test machine, append `-- pc-task` to the live check command. It uses a temporary folder and checks the copied bytes and that Notepad opens. Approve the DNS cache test in Windows when prompted.
