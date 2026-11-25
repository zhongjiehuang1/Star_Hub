# Star Intelligence

登录已授权的 Star Hub 账户，从侧栏或工作区菜单打开 Star Intelligence。首页展示当前行动、执行步骤、所需资源、完成标准和反馈入口。反馈会保存到当前行动，并安排下一次复盘。展开面板可以查看近期决策、个人和世界认知、证据、策略版本、费用和邮件记录。

## 运行方式

- Tomcat 启动后运行后台调度器。工作节点通过共享 MySQL 取得有时限的复盘租约。反馈或授权变化会使旧复盘失去发布权限。进程退出后，未释放的租约最多保留十分钟。
- 公开资料来自个人网站，外部机会通过 OpenAI 官方联网搜索获取。搜索请求只使用公开主题和上次公开研究摘要。决策请求可使用本功能的反馈，以及单独启用的账户记忆、近期已保存对话中的用户文字和 `Star Intelligence` 文件夹内的短文本。账户记忆自身也须启用。
- 决策比较多个方向并区分有来源的陈述、推断、预测和未知。当前行动仍合适时沿用原行动。输入未变化时，后台会等待下一次采集。策略经验来源于证据和用户报告，可恢复最近的策略版本。
- 工作者使用启用时选中的 OpenAI 密钥。密钥被删除或改选后，重新保存授权即可切换。模型调用前保存额度占用和服务商请求记录。可确认的用量用于估算，结果不明的调用继续占用当日预算。后台每天最多四次模型调用，含最多一次收费联网检索。
- 异常、风险和机会可触发电子邮件。系统用专用发信凭据、稳定消息 ID 和伦敦时区的发送时段处理发送。提交成功显示为服务商已接受。服务商投递事件可更新为已投递，邮箱是否入箱以收件方看到的结果为准。

## 部署

在 Star Hub 的共享数据库应用 `020_star_intelligence.sql`。运行账户需要对状态表及邮件表授予 SELECT、INSERT、UPDATE，以及对事件表授予 SELECT、INSERT。部署 WAR 前保留现有 WAR、展开目录及与之绑定的容量配置。新表是附加迁移，回滚应用时保留记录。

入口将 `POST /api/intelligence` 转发至已部署 Star Intelligence 的本机 Star Hub，并沿用账户请求的入口准入与来源校验。若 Nginx 配置由入口控制器生成，在生成器中加入此路由、POST 允许列表及账户连接限额。请求体上限为 8 KiB。生成并验证一份配置后，重启入口控制器并实测源站。

在现有 Star Hub 私有账户配置的同一目录部署 `starhub-intelligence.properties`，模式 `0640`，由部署管理员负责写入并授予 Tomcat 读取。配置作用域是一个既有账户 ID 和不可变账户代际。各入口可配置同一账户，生产工作者建议在一台持续运行的云服务器启用。

```properties
intelligence.owner.id=<既有账户 ID>
intelligence.owner.generation=<既有账户 generation>
intelligence.worker=true
intelligence.bootstrap.enabled=false
intelligence.budget.dailyMicros=4000000
intelligence.publicOrigin=https://starhub.zhongjiehuang.com
intelligence.mail.to=<已授权收件地址>
intelligence.mail.from=<Resend 已验证域名的发件地址>
intelligence.mail.fromName=starhub_notify
intelligence.mail.key=<Resend 发信密钥>
intelligence.mail.statusKey=<可读取邮件状态的 Resend 密钥>
```

`bootstrap.enabled` 仅在状态首次建立时生效。管理员已获得后台调用授权时可以设为 `true`。已保存的启停设置会跨重启保留。后台可以在邮件配置待补齐期间继续运行。邮件状态密钥省略时使用发信密钥查询，查询权限不足的邮件会保留送达未确认状态。

额度单位为美元百万分之一，默认每日上限为 4 美元。联网调用预留覆盖服务商最大上下文的 3.80 美元额度，已确认的调用按实际 token 和工具用量估算并释放剩余额度。管理员调整型号、价格或上限时应核对服务商定价和项目账单。 [OpenAI API 定价](https://developers.openai.com/api/docs/pricing)

Resend 的同一消息重试使用同一幂等键和发信载荷。队列将不确定重试限制在十二小时内，最多尝试三次。超过窗口或发信身份变更后保留未确认记录。 [Resend 幂等键](https://resend.com/docs/dashboard/emails/idempotency-keys) · [邮件状态](https://resend.com/docs/api-reference/emails/retrieve-email)

Resend 发件人显示名可选，最长 64 个字符，支持英文字母、数字、空格、下划线和连字符。配置显示名后，新生成的 HTML 通知会在顶部内嵌 Star Hub 标识。收件箱列表的发件人头像由邮箱客户端决定。 [Resend 发信接口](https://resend.com/docs/api-reference/emails/send-email) · [内嵌图像](https://resend.com/changelog/embed-images-using-cid)

## QQ 邮箱发给自身

当已授权收件地址是个人 QQ 邮箱时，工作节点也可通过该邮箱向自身发信。将 `intelligence.mail.provider` 设为 `qq`，将 `intelligence.mail.from` 和 `intelligence.mail.to` 设为同一授权地址，再将 QQ 邮箱签发的 IMAP/SMTP 客户端授权码存入私有 `intelligence.mail.key`。被动节点只需同一账户配置，可通过共享数据库查看投递状态。

通知经 QQ SMTP 的证书校验 TLS 连接提交。服务器仅在 QQ 收件箱以只读方式找到对应消息 ID 和正确地址后记录「已在 QQ 收件箱核验」。SMTP 提交结果不明确时只查收件箱。已知在提交前失败的连接可以重试。
