# 学习计时 V1 → 双人学习室 V2 集成审核

基线：`uuuumagnetic/linjian-peek-public` 的 `main`，`67d23df084f21c91c60322890cc740c57152c63f`。
两份上传补丁已在完整源码上按 V1、V2 顺序应用，再进行下列修正。既有 MCP 私有入口认证保留。

## 功能与数据边界

- 旧“学习计时”弹窗与 V2 学习室均每秒读取同一持久化计时状态。弹窗开始/暂停/继续后保持显示，按当前状态更新按钮和今日分科目统计；关闭或 Activity 暂停时取消刷新，返回时恢复。
- 守护页保留“学习计时”，新增“双人学习室”；开始、暂停、继续、结束保存、每日累计与今日分科目统计均使用手机本地数据。计时与全机专注锁定互相独立。
- 跨午夜按手机自然日分摊；暂停不计时；最近 50 次完成记录、本机最近 60 条留言保留。科目统计独立累计，不依赖最近 50 次记录。旧 V1 的历史总计保留，缺失的旧分科目数据不会杜撰回填。
- 留言同步默认关闭。用户开启后，原有服务的下一次 LifeState 同步携带最近 12 条；关闭后下一次同步以空快照覆盖。离线时不能撤回服务器已有快照或供应商保存的数据。同步不属于端到端加密。
- AI 回复默认关闭。手机用户明确开启并点击发送后才提交最近 8 条有效用户/AI 消息；系统错误提示不进入上下文。AI 请求只走 HTTPS，不跟随重定向；手机不保存模型供应商密钥。
- 未开启 AI、无网络、未配置或限额耗尽时仍保存本机留言，计时继续。可跳转 ChatGPT 应用，未安装时打开网站；不保证直达某一会话。
- 独立 Cloudflare AI 不是当前 ChatGPT 会话，不能自动读取其历史或主动唤醒它。MCP `get_study_room_messages` 只读取已同步快照。

## 安全与费用修正

- Worker 与 Python 新端点均要求 `X-Auth-Token`，拒绝仅 URL token 的请求；请求体最大 32 KiB、严格角色/内容/设备 ID 校验。
- Android 循环读取完整响应、16 KiB 上限、关闭输入流与连接；拒绝重定向，避免自定义认证头跟随跳转泄漏。
- Python 代理只使用服务端配置的 HTTPS 上游，拒绝 URL 用户名/密码、query、fragment；禁用重定向、响应上限 16 KiB，保留 429 限额语义，不向客户端泄漏供应商异常。
- Worker 需要 `STUDY_ROOM_FREE_AI_ENABLED=true`、D1 `DB` 和 `AI` 三者齐备才调用模型；Python 代理也需同名显式开关及私有上游 URL/token。仓库未添加 AI binding、密钥或启用配置。
- D1 在调用前原子预占额度；整个服务每天最多 30 次，UTC 零点切换。所有 device_id 共用限额，失败尝试也占额度，无自动重试、无收费后备模型。
- 30 次限额不是账单保证。日后启用前必须由账号所有者确认 Workers **Free** 计划与账户其他用量；不要升级付费计划或开通付费后备服务。代码开关无法证明实际账户账单计划。

官方参考（核对日期 2026-10-12）：
- https://developers.cloudflare.com/workers-ai/platform/pricing/
- https://developers.cloudflare.com/workers-ai/models/llama-3.1-8b-instruct-fp8/

## 验证

本地完整 `android/build.sh` 已成功：Android SDK platform 34、build-tools 34.0.0、JDK 17，资源编译/链接、全部 Java 编译、DEX、APK 对齐、签名与证书 SHA-256 校验均通过。只有既有 deprecated API/source 11 警告。

- `npm test --prefix mcp`：2 项既有私有入口/HTTP/SSE 回归通过。
- `node --test tests/test_study_room_worker.mjs`：3 项接口鉴权、默认关闭、体积/类型边界、45 个并发请求仅 30 个调用、换设备 ID、错误脱敏回归通过。
- `python3 tests/test_study_room_proxy.py`：5 项参数/认证、关闭状态、代理响应、重定向拒绝、429、超长响应测试通过；上游为 mock，无真实模型调用。
- `python3 tests/test_study_quota_sql.py`：SQLite 执行生产 D1 兼容 SQL，首次 30 次允许、第 31 次拒绝。
- `JSON_JAR=/path/to/json-20240303.jar python3 tests/test_study_android.py`：23 条生产计时/聊天类断言通过；使用真实 org.json，Context/preferences 为测试替身。
- Node/Python 语法与 `git diff --check` 通过。

PR 工作流重复完整 Android 构建和上述回归；审核分支/PR 不上传 APK artifact，不创建 Release，不部署。

## 仍需后续验收

未安装到手机，未测试真机 UI、中文键盘、小屏幕、后台省电/强停/重启、真实 Workers AI、真实 Railway/Worker 通信与账号额度。当前计时基于手机墙上时钟，手动调整时间/时区可能影响分摊；单个运行段最多计入 7 天以限制损坏时间戳的计算。实际学习需用户手动开始和结束，不是有效学习检测器。

最终复核发现旧 V1 弹窗原本只展示打开时快照，已补齐上述实时刷新；该修正另行完整 Android 构建通过，未把编译成功表述为真机 UI 验收通过。

本次只创建审核 PR；未合并、部署、发布 APK 或启用付费服务。
