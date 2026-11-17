; Keep AppId and the per-user data directory stable across releases.
[Setup]
AppId=ELDataPlatform.Windows
AppName=EL 数仓平台
AppVersion=@@VERSION@@
VersionInfoVersion=1.0.0.0
AppPublisher=EL Data Platform
DefaultDirName={localappdata}\Programs\ELDataPlatform
DefaultGroupName=EL 数仓平台
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.19045
OutputDir=@@OUTPUT_DIR@@
OutputBaseFilename=@@OUTPUT_NAME@@
Compression=lzma2/fast
SolidCompression=no
DiskSpanning=no
WizardStyle=modern
SetupMutex=Local\ELDataPlatform.Setup
AppMutex=Local\ELDataPlatform.Launcher
CloseApplications=no
RestartApplications=no
UninstallDisplayIcon={app}\el-launcher.exe
UsePreviousAppDir=yes
UsePreviousTasks=yes

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "快捷方式："

[Icons]
Name: "{autodesktop}\EL 数仓平台"; Filename: "{app}\el-launcher.exe"; Parameters: "start"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{group}\EL 数仓平台"; Filename: "{app}\el-launcher.exe"; Parameters: "start"; WorkingDir: "{app}"
Name: "{group}\停止服务"; Filename: "{app}\el-launcher.exe"; Parameters: "stop"; WorkingDir: "{app}"
Name: "{group}\运行状态"; Filename: "{app}\el-launcher.exe"; Parameters: "status"; WorkingDir: "{app}"
Name: "{group}\查看初始账号"; Filename: "{app}\el-launcher.exe"; Parameters: "login"; WorkingDir: "{app}"
Name: "{group}\运行日志"; Filename: "{app}\el-launcher.exe"; Parameters: "logs"; WorkingDir: "{app}"
Name: "{group}\使用说明"; Filename: "{app}\README.md"

[Run]
Filename: "{app}\el-launcher.exe"; Parameters: "start --after-setup"; Description: "启动 EL 数仓平台"; WorkingDir: "{app}"; Flags: postinstall nowait skipifsilent runasoriginaluser

[Messages]
FinishedLabel=安装完成。首次启动将配置运行环境、导入数据库镜像并打开平台，可能需要数分钟。若系统要求授权或重启，请完成后再次从桌面启动。
ConfirmUninstall=是否卸载 EL 数仓平台程序？%n%n数据库、任务和本机配置将保留。请先从开始菜单选择“停止服务”；卸载不会删除 Docker、WSL 或数据卷。

; Deliberately no [UninstallDelete]: application state and Docker volumes are
; outside {app}. Never remove them as part of an upgrade or uninstall.
