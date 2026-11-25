#define Product "Star Hub"
#define InstallName "StarHub-Windows-x64-Setup"

[Setup]
AppId={{DBCF9DCE-8C6E-4A9B-88C3-AD65099013B1}
AppName={#Product}
AppVersion={#Version}
AppPublisher=Star Hub
AppPublisherURL=https://starhub.zhongjiehuang.com/
AppSupportURL=https://github.com/zhongjiehuang1/Star_Hub/issues
DefaultDirName={localappdata}\Programs\Star Hub
DefaultGroupName={#Product}
DisableStartupPrompt=yes
DisableWelcomePage=yes
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableFinishedPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64os
MinVersion=10.0.{#MinimumWindowsBuild}
UninstallDisplayIcon={app}\StarHub.exe
CloseApplications=yes
RestartApplications=no
OutputDir={#ReleaseDir}
OutputBaseFilename={#InstallName}
SetupIconFile=..\StarHub.Windows\starhub.ico
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
VersionInfoVersion={#Version}
VersionInfoProductName={#Product}
#ifdef SignStarHub
SignTool=StarHub
SignedUninstaller=yes
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "*.pdb"

[Icons]
Name: "{userprograms}\Star Hub"; Filename: "{app}\StarHub.exe"; WorkingDir: "{app}"; AppUserModelID: "StarHub.Desktop"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "StarHubWorker"; ValueData: """{app}\worker\StarHub.Worker.exe"""; Flags: uninsdeletevalue; Check: RegisterWorkerAtSignIn
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "StarHubDesktop"; ValueData: """{app}\StarHub.exe"""; Flags: uninsdeletevalue; Check: RegisterDesktopAtSignIn
Root: HKCU; Subkey: "Software\StarHub"; ValueType: dword; ValueName: "WorkerStartupConfigured"; ValueData: "1"; Flags: uninsdeletevalue uninsdeletekeyifempty
Root: HKCU; Subkey: "Software\StarHub"; ValueType: dword; ValueName: "DesktopStartupConfigured"; ValueData: "1"; Flags: uninsdeletevalue uninsdeletekeyifempty

[Run]
Filename: "{app}\StarHub.exe"; WorkingDir: "{app}"; Flags: nowait runasoriginaluser skipifsilent

[Code]
const
  WM_COMMAND = $0111;

function GetDlgCtrlID(Window: HWND): Integer;
  external 'GetDlgCtrlID@user32.dll stdcall';

function RegisterWorkerAtSignIn(): Boolean;
begin
  Result := not RegValueExists(HKCU, 'Software\StarHub', 'WorkerStartupConfigured') or
            RegValueExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'StarHubWorker');
end;

function RegisterDesktopAtSignIn(): Boolean;
begin
  Result := not RegValueExists(HKCU, 'Software\StarHub', 'DesktopStartupConfigured') or
            RegValueExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'StarHubDesktop');
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if (CurPageID = wpReady) and not WizardSilent then
  begin
    { Inno 6 first selects wpReady before the form is visible. Queue its own
      Next button notification so Setup starts after the form is shown. }
    if not PostMessage(WizardForm.NextButton.Parent.Handle, WM_COMMAND,
       GetDlgCtrlID(WizardForm.NextButton.Handle) and $FFFF, LongInt(WizardForm.NextButton.Handle)) then
      Log('Could not queue automatic installation. The Install button remains available.');
  end;
end;

function StopWorker(): Boolean;
var
  Worker: String;
  ResultCode: Integer;
begin
  Worker := ExpandConstant('{app}\worker\StarHub.Worker.exe');
  Result := not FileExists(Worker);
  if not Result then
    if Exec(Worker, '--stop', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      Result := ResultCode = 0;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if not StopWorker() then
    Result := 'Close the Star Hub background worker before updating.';
end;

function InitializeUninstall(): Boolean;
begin
  Result := StopWorker();
  if not Result then
    SuppressibleMsgBox('Close the Star Hub background worker before uninstalling.', mbError, MB_OK, IDOK);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'StarHubWorker');
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'StarHubDesktop');
  end;
end;
