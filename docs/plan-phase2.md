# 🐾 第三阶段（M3）细化规划：会话持久化 + 聊天体验 Chatbox 化

> 主人拍板（2026-08-15）：文件管理往后排，优先追平 Chatbox 体验——会话持久化、聊天界面 Chatbox 化、模型管理。
> 真终端已提前到 M2 完成（DSH 跑在真终端里 + 聊天走 socket）。
> ✅ 2026-08-15 真机验收通过：SQLite 持久化 + Chatbox 化布局 + 模型切换/思考强度 + 网络搜索 + 工具全可用（bash/read/write/edit/str_replace_editor/web_search/todo_write）。

## 一、目标与验收

1. ✅ 会话持久化：App / DSH 进程重启后，继续同一会话，模型记得上下文（SQLite backend）
2. ✅ 聊天界面 Chatbox 化：左侧会话抽屉 + 顶栏新会话 + 输入栏工具栏（模型 / 网络搜索 / 思考强度 / 上传文件）
3. ✅ 模型管理：切换模型 + 设置思考强度（规划文档补条目）
4. ✅ 网络搜索：DeepSeek 官方 web_search（全局开关，重启生效）
5. ✅ 输入框 bug 修复 + 流式长文本抽搐修复
6. ⏳ 文件管理（数据中心）：后移到 M4

## 二、任务分解

| 任务 | 内容 | 交付/验证 |
|---|---|---|
| M3.1 | 会话持久化：session-persistence-sqlite（node:sqlite 无原生依赖，规避 jsonl 的 link() 在 Android SELinux 下 EACCES） | 重启后同会话追问能复述上下文 |
| M3.2 | 聊天界面 Chatbox 化：抽屉会话管理 + 顶栏新会话 + 输入栏工具栏 | 布局四要素齐全 |
| M3.3 | 模型管理：模型切换（session/setModel + installModelSelection）+ 思考强度（off/high/max） | 切换后下一条消息走新模型 |
| M3.4 | 网络搜索：tool-web + web-search-deepseek（复用 DEEPSEEK_API_KEY），全局开关 | 开关后出现 web_search 工具卡片 |
| M3.5 | 上传文件：文本直接发 + 其他文件缓存路径让模型 read 工具读 | 文本复述 / read 工具读到 |
| M3.6 | 输入框 bug + 流式长文本抽搐修复 | 输入不跳高、流式不卡 |
| M4 | 文件管理（数据中心）| 后移 |

## 三、风险

| # | 风险 | 对策 |
|---|---|---|
| 1 | node:sqlite 在 Termux node 26.4 的可用性 | 已确认 Node 22.5+ 内置；真机验证，WAL 有问题降级 journalMode=delete |
| 2 | 图片/二进制上传 | DeepSeek 不支持图片输入、read 仅文本 → 标注后续（多模态 / 文件解析） |
| 3 | 网络搜索 per-request 即时开关 | 先全局开关（重启 DSH，SQLite 自动 resume）；per-request 后续 |

---

*—— 主人呜咕的专属喵喵助手 · 樱茈 🐾*
