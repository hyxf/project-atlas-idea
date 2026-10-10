# Project Atlas

![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)
![IntelliJ Platform](https://img.shields.io/badge/platform-IntelliJ-orange.svg)

一款 IntelliJ IDEA 效率插件，整合 AI 代码上下文、Git 仓库导航、常用提交信息与 Terminal 命令、本地项目管理，以及 IDE 代理切换和插件更新。

---

## 🎯 插件简介

Project Atlas 将 AI 代码上下文、Git/Terminal 工作流、本地项目管理和代理工具整合到 IntelliJ IDEA 中。
AI 上下文功能会在项目根目录管理 `.aicode.json`，支持多个上下文组，并可将所选组或全部组导出为 Markdown。

### 🌐 HTTP/HTTPS 代理

点击 IDEA 底部状态栏的 Project Atlas 代理状态，或从 **Tools → Project Atlas → Proxy** 打开菜单，可在 Direct 和 `~/.project-atlas/proxy.json` 中的命名代理间切换、检查当前 HTTP 代理并打开配置文件。IDE 当前设置中的其他代理会作为 `system` 项显示。配置文件缺失时会创建 Local 默认项；支持 `{ "proxy": "http://..." }` 和 `"proxies"` 字符串数组旧格式。PAC/SOCKS 等无法直接检查的代理会提示原因。

---

## ✨ 功能特性

### 📁 文件管理

* **添加到上下文**：在 Project 视图中右键任意文件 → AICode → Add to AICode
* **从上下文移除**：右键已管理文件 → AICode → Remove from AICode
* **智能菜单**：根据文件当前状态自动显示 Add 或 Remove
* **支持撤销**：所有操作均支持 IntelliJ 的 Undo / Redo

---

### 🖼️ 可视化工具窗口

* 提供 **AICode Context** 工具窗口，展示所有已管理文件
* 多模块项目中显示模块名：`[module-name] FileName.java`
* 点击即可打开文件
* 右键可移除文件
* 工具栏按钮可直接打开 `.aicode.json`
* 文件变化时自动刷新

---

### 🔄 自动同步

* **文件删除**：文件被删除时自动从上下文移除
* **文件重命名**：路径自动更新
* **文件移动**：移动目录后路径自动更新
* 所有变更都会立即同步到 `.aicode.json`

---

### 📋 Markdown 导出

* **一键导出**：右键 `.aicode.json` → AICode → Copy as Markdown
* 自动生成包含所有文件内容的完整 Markdown 文档
* 直接复制到系统剪贴板
* 支持主流文件类型并带语法高亮
* 导出完成后会提示包含的文件数量

### 🔀 Git 与 Terminal 工具

* 在 Project 视图或编辑器标签菜单中打开 Git 远端仓库主页、当前分支、所选文件或目录对应的远端页面，并复制 origin URL
* 在 Git 提交信息输入区选择常用提交信息；可在 **Settings / Preferences → Tools → Common Commit Messages** 管理
* 在独立的 **Project Atlas: Common Commands** Tool Window 管理命令，或从 Terminal 的 **Common Commands...** 快速选择；按标签分组，支持多标签、拖动排序、变量和终端插入/执行
* 在文件菜单中将选中文件的相对路径插入 Terminal，支持在光标处插入

#### Common Commands

从 **Tools → Project Atlas: Common Commands** 或右侧 Tool Window 打开。视图顶部工具栏提供新增、全局变量、打开原始 JSON、刷新、展开和折叠；右键命令可插入终端、执行、编辑、快速改标签或删除。双击命令会插入终端而不执行。删除只移除 `commoncmd.json` 中的记录。命令可出现在多个标签组；无标签命令显示在 **Untagged**。把一个命令拖到另一个命令上可调整 JSON `commands` 数组顺序。

配置直接共享 `~/.project-atlas/commoncmd.json`。文件缺失时创建一次，写入 `git status`、`git diff`、`git log --oneline -10` 三个默认命令。新增/编辑支持多行命令和说明、标签、命令变量，并在弹框内列出每个变量的引用名、标签、类型和必填状态；全局变量使用同一变量编辑器。编辑器左侧可新增、选择或移除变量，右侧按类型显示必填、默认值、选项和路径种类；保存时会检查变量名、重复名称及选项默认值，取消则丢弃本次编辑。变量支持 `text`、`select`、`multiSelect`、`path` 及 `file`、`folder`、`any` 路径类型。在命令中写 `${name}` 即可在插入或执行时询问；未声明引用原样保留，取消则不发送命令。同名命令变量和全局变量会报错。输入值只用于本次操作，并按配置的终端 shell 转义。

插入会复用当前终端会话，若没有会话则创建新会话，发送解析后的文本但不发送回车。为避免终端把嵌入的换行当作执行，多行命令只能通过 **Run Command** 执行。执行会在新终端会话发送命令和回车，使输出保持可见；工作目录优先使用当前编辑文件所在的项目内容根目录，否则使用项目根目录。Terminal 菜单的 **Add Current Command** 优先读取经典终端组件的选区，无法读取时使用剪贴板文本作为初始值；IntelliJ 2023.2 没有与 VS Code `terminal.copySelection` 对等的稳定选区 API，在新版终端中请先复制选区。保存时会检查未保存的 JSON 编辑器、文件外部变化和 `<文件名>.lock` 锁，冲突时请先保存或刷新。写入保留未知字段并使用同目录临时文件原子替换。
* 从 **Tools → Project Atlas** 生成或更新 CHANGELOG，并按语义化版本创建及推送 Git 标签

### 🌐 插件更新

可从 **Tools → Project Atlas → Check for Project Atlas Updates** 检查自定义插件仓库中的版本；发现更新后可通过 IDE 下载并安装，重启后生效。

---

### 🗂️ Project Atlas 项目管理

**Project Atlas: GitHub Repositories** 是独立工具窗口，与 **Project Atlas: Projects** 分开。打开 GitHub 窗口时只读取本地缓存；点击 **Refresh** 才会验证 token 对应的用户并分页同步仓库。仓库按 Public / Private 和语言分组，支持展开/折叠、双击打开 GitHub、复制 SSH URL，以及通过 SSH 克隆并加入 Projects。此插件不提供 Git Repositories 浏览视图。

* 保存当前项目，或选择任意本地目录添加项目
* 扫描一个或多个目录，识别 IntelliJ、Git、Gradle、Maven、Node.js、Rust 和 Go 项目，预览后批量导入
* 使用 **All / Recent / Favorites** 列表视图或 **Tags** 分组视图，并按名称、路径、最近打开或最近保存排序
* 按名称、绝对路径和标签搜索，名称匹配结果优先展示
* 支持当前窗口或新窗口快速打开项目，也可从 IDE 欢迎页浏览已保存项目
* 编辑名称、路径、标签和收藏状态，并可创建、重命名和删除标签
* 复制项目目录或路径、在 Finder / Explorer 中显示，以及通过 Terminal 插件打开目录
* 项目目录移动后可重新定位记录，缺失目录会被明确标记
* 可仅移除管理记录，也可经确认后将整个项目目录移到系统废纸篓或永久删除

---

## 🚀 使用方式

### 🧠 在 IntelliJ IDEA 中安装插件仓库

1. 打开 **IDEA**
2. 进入 **Settings**（或 Preferences）
3. 选择 **Plugins**
4. 右上角点击 **⚙️（齿轮图标）**
5. 选择 **Manage Plugin Repositories**
6. 点击 **+**
7. 填入地址：

```
https://hyxf.github.io/project-atlas-idea/updatePlugins.xml
```

8. 确认保存
9. 搜索插件名称或在 **Updates** 中检查更新

### 📦 从本地 ZIP 安装

在 Plugins 页面点击齿轮图标，选择 **Install Plugin from Disk...**，然后选择
`build/distributions/` 中生成的插件 ZIP。不要解压 ZIP。

---

## 🗂️ 项目管理使用指南

安装并重启 IDE 后，可从左侧 **Project Atlas** Tool Window 或 **Tools → Project Atlas** 进入：

* **Save Current Project...**：保存当前项目；重复保存同一路径会更新已有记录
* **Add Project...**：选择目录并设置名称、标签和收藏状态
* **Import Local Projects...**：选择目录和扫描深度，检查识别结果后批量导入；可选择是否更新已有记录
* **Open Project... / Open Project in New Window...**：从轻量 Quick Open Popup 打开项目
* **Search Projects**：按名称、路径或标签查找项目
* **Switch Project View**：在列表与标签视图之间切换

在 Tool Window 中双击项目即可按默认方式打开。右键菜单还提供编辑、复制项目、编辑标签、收藏、
复制路径、显示目录、在终端打开、定位缺失项目、删除项目目录和仅移除记录等操作。

> **数据安全：** **Remove from Project Atlas...** 只删除管理记录，不影响磁盘文件；
> **Delete Project...** 会删除磁盘上的整个项目目录。删除对话框可选择直接永久删除或尝试移到系统废纸篓，
> 当前正在打开的项目不能被删除。

### 快捷键

| 操作 | Windows / Linux | macOS |
| --- | --- | --- |
| 搜索项目 | `Ctrl+Shift+P` | `⌘⇧P` |
| 切换列表 / 标签视图 | `Ctrl+Shift+T` | `⌘⇧T` |
| 显示 / 隐藏 Tool Window | `Ctrl+Shift+,` | `⌘⇧,` |

若快捷键与现有 Keymap 冲突，可在 **Settings / Preferences → Keymap → Project Atlas** 中重新绑定。

### 设置与数据

在 **Settings / Preferences → Tools → Project Atlas** 中可以设置默认在当前窗口或新窗口打开项目，
以及列表视图与标签视图中的项目间距。排序方式、当前视图和列表筛选也会随使用状态保存。项目搜索支持名称、绝对路径和标签，名称匹配优先；列表可按名称、路径、最近打开或最近保存排序。

项目、标签和设置继续存储在以下用户级配置中，兼容原 Project Atlas 插件的数据：

```text
~/.project-manager/project.json
```

Tool Window 工具栏可直接打开该文件。配置采用原子替换写入，并保留插件无法识别的 JSON 字段；
如果文件损坏，插件会继续使用最后一次有效数据并阻止覆盖写入，修复文件后刷新即可重新加载。

GitHub 与 Git Repositories 继续使用 VS Code 扩展共享的数据格式：

```text
~/.project-atlas/github.json
~/.project-atlas/repos.json
```

GitHub token、用户、代理和仓库缓存保存在 `github.json`。首次使用 GitHub 工具窗口时，只在文件不存在时创建配置文件，不会自动请求网络；从工具栏可编辑 GitHub 设置或打开配置文件。写入配置前会比较磁盘内容并使用同目录临时文件原子替换；检测到 VS Code 等外部编辑器并发修改时会提示重新加载后重试，不会静默覆盖。

---

**为热爱 AI 辅助编程的开发者打造** 🚀

源码仓库：[hyxf/project-atlas-idea](https://github.com/hyxf/project-atlas-idea)

## 🔧 兼容性验证

插件以 IntelliJ IDEA 2023.2（build 232）作为最低兼容版本，并在插件描述符中声明兼容至
2025.3（build 253）分支。构建配置默认验证 2023.2；可指定本地 IDE 安装目录执行额外的二进制兼容检查：

```bash
./gradlew runPluginVerifier -PpluginVerifierIdePath="/path/to/IntelliJ IDEA.app/Contents"
```
