# Project Atlas

![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)
![IntelliJ Platform](https://img.shields.io/badge/platform-IntelliJ-orange.svg)

一款 IntelliJ IDEA 效率插件，用于管理 AI 代码上下文、常用 Git/Terminal 操作和本地项目集合2。

---

## 🎯 插件简介

Project Atlas 将 AI 代码上下文、常用 Git/Terminal 操作和本地项目管理整合到 IntelliJ IDEA 中。
其中 AI 上下文功能会在项目根目录管理 `.aicode.json`，并支持按上下文组组织文件和导出 Markdown。

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

---

### 🗂️ Project Atlas 项目管理

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
以及列表视图与标签视图中的项目间距。排序方式、当前视图和列表筛选也会随使用状态保存。

项目、标签和设置继续存储在以下用户级配置中，兼容原 Project Atlas 插件的数据：

```text
~/.project-manager/project.json
```

Tool Window 工具栏可直接打开该文件。配置采用原子替换写入，并保留插件无法识别的 JSON 字段；
如果文件损坏，插件会继续使用最后一次有效数据并阻止覆盖写入，修复文件后刷新即可重新加载。

---

**为热爱 AI 辅助编程的开发者打造** 🚀

源码仓库：[hyxf/project-atlas-idea](https://github.com/hyxf/project-atlas-idea)

## 🔧 兼容性验证

插件以 IntelliJ IDEA 2023.2（build 232）作为最低编译基线，并声明兼容至经过验证的
IntelliJ IDEA 2025.3（build 253）分支。除常规构建外，可指定本地高版本 IDE 执行二进制兼容检查：

```bash
./gradlew runPluginVerifier -PpluginVerifierIdePath="/path/to/IntelliJ IDEA.app/Contents"
```
