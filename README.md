# SystemWin

**SystemWin** —— 一个运行在 Windows 上、用来复刻 `systemctl` 的
命令行工具。它用 Java 编写、用 Gradle 构建。它能通过 Windows 的**服务控制管理器
（SCM）** 来管理 Windows 服务，同时尽量模仿 systemctl 的命令行语法、输出风格、
退出码以及单元（unit）命名习惯。


## 功能特性

- systemctl 风格的命令：`install`、`uninstall`、`start`、`stop`、`restart`、
  `status`、`enable`、`disable`、`is-active`、`is-enabled`、`list-units`、
  `list-unit-files`、`daemon-reload`、`version`、`language`、`help`。
- `systemwin install -n <名称> [-p <工作目录>] -e <可执行文件> [-c <参数...>]`
  可以把**任意**程序变成服务：`-p` 指定工作目录，`-e` 指定可执行文件（绝对路径
  或相对路径都行），`-c` 后面跟的内容都会原样作为程序的参数（所以像 `--flag`
  这种选项也能原样传过去，不会被误解析）。
- `systemwin install -s <文件.service>` 可以导入一个 systemd 单元文件
  （支持 `[Unit] Description`、`[Service] ExecStart/WorkingDirectory/Environment/
  Restart`、`[Install] WantedBy`）。
- **内置服务宿主**：默认的 `install` 会通过 SystemWin 自带的宿主
  （`systemwin.exe --host <名称>`，服务逻辑参照
  [fightroad/nssm](https://github.com/fightroad/nssm) 项目）来托管任意程序：
  宿主本身是服务二进制，负责以子进程方式拉起目标程序（带工作目录、环境变量、
  stdout/stderr 日志重定向），程序崩溃后自动重启，停止服务时终止整个进程树。
  而 `install --direct` 则是把程序本身直接注册成服务二进制（这种方式只适用于
  真正的 Windows 服务程序）。
- 单元名称可以带后缀 `.service` 也可以不带，两种写法都能识别。

## 环境要求

- Windows 10/11（依赖 `sc.exe`、`reg.exe` 和 PowerShell 5.1+，这些都是系统自带
  的，不用额外装）。
- 服务操作（`install`/`uninstall`/`start`/`stop`/`enable`/`disable`）需要管理员
  权限。SystemWin 在普通控制台运行这些命令时会自动弹 UAC 请求提权（用
  `--no-elevate` 可以退出自动提权），如果提权不可行会明确给出"拒绝访问"的报错。
  查询类命令（`status`、`is-active`、`is-enabled`、`list-*`）普通用户就能用。
## 构建

```bat
gradlew build            REM 编译 jar
gradlew singleExe        REM 打出唯一的 build\singleexe\systemwin.exe
```


| 路径 | 说明 |
| --- | --- |
| `build/singleexe/systemwin.exe` | **交付物**：一个自包含的 exe |
| `build/libs/systemwin.jar` | 内部产物：应用 jar（塞在 exe 里） |

开发时也可以直接用 jar 跑：`gradlew run --args="help"`，或
`java -jar build/libs/systemwin.jar help`。

## 用法

```
systemwin [命令] [选项] [单元...]
```

### 创建 / 导入服务

```bat
REM 用内置服务宿主托管任意程序（工作目录、环境变量、
REM stdout/stderr 日志、崩溃自动重启、停止时杀进程树）
systemwin install -n myweb -p D:\app -e node.exe -c app.js --port 8080
systemwin install -n myjar -p D:\app -e java.exe -c -jar app.jar
systemwin install -s C:\units\nginx.service
systemwin install -f -n myweb -p D:\app -e node.exe          REM 覆盖已存在的服务
systemwin uninstall myweb

REM 直接把程序本身注册成服务二进制（只适用于真正的服务程序）
systemwin install --direct -n svc -e C:\bin\realservice.exe
```

- `-p <目录>` —— 工作目录（可省略，缺省用可执行文件所在目录）
- `-e <文件>` —— 要跑的程序；可以是绝对路径（`D:\Java.exe`）或相对路径
  （`java.exe`、`.\java.exe`；相对路径按工作目录和 PATH 解析）
- `-c <参数...>` —— `-c` 之后输入的全部内容都作为程序的参数
  （例如 `-jar app.jar`、`--port 8080`），所以 `--flag` 这种选项能原样传过去

`install` 会把 systemd 单元字段映射到托管服务上：

| 单元文件字段 | 作用 |
| --- | --- |
| `ExecStart=` | 托管的程序 + 参数 |
| `WorkingDirectory=` | 托管程序的工作目录 |
| `Environment=` | 托管程序的环境变量 |
| `Restart=no` | 关闭崩溃自动重启 |
| `[Install] WantedBy=` | 启动类型设为 `SERVICE_AUTO_START`（否则 `SERVICE_DEMAND_START`） |
| `Description=` | 服务的 `DisplayName` / `Description` |

程序的 stdout/stderr 会记录到
`%ProgramData%\SystemWin\logs\<名称>.out.log` / `<名称>.err.log`；宿主的生命周期
事件记录在 `<名称>.host.log`。

### 提权

任何写命令（`install`、`uninstall`、`start`、`stop`、`restart`、`enable`、
`disable`），如果不是在已提权的控制台里运行，会先打印一句提示再弹 **UAC 授权框**；
提升后的运行发生在隐藏窗口里，它的输出会被回传到你的控制台。如果弹窗被取消或
会话无法弹窗，会打印清晰的错误提示。在脚本里可以用 `--no-elevate` 退出自动提权，
此时会直接得到 "Access denied" 报错。

```bat
systemwin install --no-elevate -n myweb -p C:\tools\myweb.exe
```

### 管理服务

```bat
systemwin start   myweb
systemwin stop    myweb
systemwin restart myweb
systemwin status  myweb
systemwin enable --now  myweb      REM 设置开机自启并立即启动
systemwin disable --now myweb      REM 取消开机自启并立即停止
```

### 查询状态

```bat
systemwin is-active  myweb         REM 输出 active/inactive（退出码 0/3）
systemwin is-enabled myweb         REM 输出 enabled/static/disabled（退出码 0/1）
systemwin list-units               REM 所有 Windows 服务的表格
systemwin list-unit-files          REM 单元文件 + 启用状态
systemwin daemon-reload            REM 兼容性空操作（单元实时读取）
systemwin status                   REM 系统概要
```

### 退出码

`0` 成功 · `1` 出错 · `3` 非活动（`is-active`）· `4` 单元不存在
（`status`、`is-active`、`is-enabled`）。

## 工作原理

- **查询**：通过 PowerShell 调用 `Get-CimInstance Win32_Service`
  （用 `-EncodedCommand` 传脚本，杜绝引号转义问题）。其中的取值（`Running`、
  `Stopped`、`Auto`、`Manual`、`Disabled`……）是语言无关的枚举，不会受系统
  语言影响。
- **操作**：用 `sc.exe`（创建服务用 `New-Service` cmdlet，所以 binPath 里即使
  带空格/引号也安全）。错误信息会从 Win32 数值退出码映射成本地化文案。
- **提权**：重新拉起单文件 `systemwin.exe`（从 jar 跑时就是
  `java.exe -jar systemwin.jar`）并用 `Start-Process -Verb RunAs` 提权；提升后的
  子进程在隐藏窗口里、以内部 `__elevated <log>` 模式运行，把输出 tee 到 UTF-8
  日志里，父进程再把日志回传显示。
- **服务宿主**（`systemwin.exe --host <名称>`）：实现了 SCM 协议
  （`StartServiceCtrlDispatcherW` / `SetServiceStatus` / 控制处理器），从注册表的
  `Parameters` 值里读取配置并拉起目标程序，把 stdout/stderr 重定向到日志文件，
  程序崩溃后按延迟自动重启，停止时先发控制台 Ctrl-C（可选优雅停止）、再用
  Job Object / `taskkill /T` 杀整个进程树。服务逻辑参照
  [fightroad/nssm](https://github.com/fightroad/nssm) 项目。
## 项目结构

```
src/main/java/com/systemwin/
  Main.java                  入口：横幅 + 命令分发
  Banner.java               固定的 ASCII 艺术横幅
  Version.java              版本号 / 作者常量
  I18n.java                 语言检测、切换、资源加载
  cli/Args.java             命令行参数解析
  commands/                 每个命令一个类
  service/WindowsServiceManager.java   SCM 访问（sc.exe / CIM / New-Service）
  service/UnitFileParser.java         systemd 单元文件解析
  util/ProcessRunner.java            进程 + PowerShell 执行器（带超时）
  util/Elevation.java                UAC 自提权（重跑 + 回传）
  util/TeePrintStream.java           提升子进程的输出回传
  util/Figlet.java                   figlet 渲染器（横幅；改编自 winSAB，MIT）
src/main/resources/
  standard.flf              figlet Standard 字体（横幅用）
  OUTPUT-en_US.txt          英文帮助（编译进产物）
  OUTPUT-zh_CN.txt          中文帮助（编译进产物）
  messages_en_US.properties 运行时英文消息
  messages_zh_CN.properties 运行时中文消息
src/launcher/SystemWinLauncher.cs  C# 启动器（负责自包含 exe 的解压与拉起）
src/launcher/ServiceHost.cs        C# 服务宿主（--host 模式，参照 nssm 逻辑）
```
