<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" height="120" alt="曾栖 Logo" />

# 曾栖 / Zengqi

### 由历史聊天记录重建的那个人的 Android 应用

**人物重建 · 风格画像 · 长期记忆 · 本地向量检索 · OpenAI 兼容 API**

<p>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8+" />
  <img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Architecture-Feature%20Modular-FF69B4?style=for-the-badge" alt="Modular" />
</p>

</div>

---

## 曾栖是什么

曾栖把一段真实存在过的聊天记录，重新变成一个可以继续对话的人。

导入 TXT 聊天导出文件后，App 会解析全部历史消息，分析她的说话风格（语气、口头禅、常用表情、句子长度），提取值得长期记住的回忆（事件、偏好、地点、称呼），然后以她本来的样子继续和你聊天。

聊天时可以打开"那天"回看任意一天的原始对话，或让"回忆"随机召回一段高重要性的共同记忆。

所有分析、记忆与检索全部在手机本地完成，AI 生成通过 OpenAI 兼容接口完成，应用内置默认模型，开箱即用。

## 构建

```bash
git clone https://github.com/zwany1/cq.git

cd cq

gradlew.bat :app:assembleDebug
```

JDK 17，Android SDK 35。产物在 `app/build/outputs/apk/debug/app-debug.apk`。

## 模块结构

```text
app/
core/
  common/      基础工具、设置存储、内容安全
  database/    Room（人物、导入消息、长期记忆、任务）
  domain/      跨模块接口与领域模型
  network/     AI 服务、曾栖构建引擎（风格/记忆/向量）
  security/    仓库地址等常量
  ui-common/   共享 UI 组件
feature/
  character/   人物创建与列表
  importchat/  聊天记录导入与构建
  chat/        对话（含"那天/回忆"入口）
  memory/      记忆管理
  recall/      那天/回忆弹层
  profile/     个人中心
  settings/    设置
```

## 许可

Apache License 2.0
