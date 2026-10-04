CREATE TABLE content_article (
    slug VARCHAR(80) NOT NULL,
    locale VARCHAR(8) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (slug, locale),
    INDEX idx_content_list (locale, kind, published, published_at),
    CONSTRAINT ck_content_kind CHECK (kind IN ('HELP','ARTICLE'))
);
CREATE TABLE help_search_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    query VARCHAR(100) NOT NULL,
    searched_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_help_query (user_id, query),
    CONSTRAINT fk_help_search_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
INSERT INTO content_article(slug,locale,kind,title,summary,body,published,published_at) VALUES
('notifications','en-us','HELP','Receiving notifications','Enable device notifications and choose the events you want to receive.','Open Me → Notifications. Allow notifications on this device, enable receiving notifications, select event types and save. Use the test notification to verify delivery.\n\nSystem permission alone does not register the device. If registration fails, review the diagnostic status. The server must have Firebase credentials configured. Bark is an optional separate channel. A successfully queued message does not guarantee device delivery.',true,'2026-10-04 00:00:00'),
('notifications','zh-cn','HELP','接收通知','允许本设备通知，并选择需要接收的事件。','打开「我的 → 通知信息」，允许本设备通知，开启接收通知，选择事件类型并保存。使用测试通知验证接收。\n\n系统授权不等于设备注册成功。注册失败时请查看诊断状态，服务器需要配置 Firebase 凭据。Bark 是独立的可选通道。消息入队成功不代表设备已经收到。',true,'2026-10-04 00:00:00'),
('notifications','zh-tw','HELP','接收通知','允許本裝置通知，並選擇需要接收的事件。','開啟「我的 → 通知資訊」，允許本裝置通知，開啟接收通知，選擇事件類型並儲存。使用測試通知驗證接收。\n\n系統授權不等於裝置註冊成功。註冊失敗時請查看診斷狀態，伺服器需要設定 Firebase 憑證。Bark 是獨立的選用通道。訊息入列成功不代表裝置已經收到。',true,'2026-10-04 00:00:00'),
('plan-basics','en-us','HELP','Creating a buying plan','Choose an exchange connection, assets, allocation and schedule.','Create an exchange API connection with the permissions required for spot trading. Never enable withdrawal permissions for Bitpongo.\n\nCreate a plan, choose assets and allocation, enter an amount per cycle and set its schedule time zone. Review available funds and exchange minimum order requirements. Buy-the-dip is optional. Pausing prevents future scheduled executions; it does not undo filled orders. Investment involves risk.',true,'2026-10-04 00:00:00'),
('plan-basics','zh-cn','HELP','创建买入策略','选择交易所连接、币种、比例和执行时间。','添加具有现货交易所需权限的 API 连接，不要为 Bitpongo 开启提现权限。\n\n创建策略，选择币种及比例，填写每轮金额和执行时区。检查可用余额及交易所最小下单要求。逢低买入可不开启。暂停只阻止之后的计划执行，不会撤销已成交订单。投资存在风险。',true,'2026-10-04 00:00:00'),
('plan-basics','zh-tw','HELP','建立買入策略','選擇交易所連線、幣種、比例和執行時間。','新增具備現貨交易所需權限的 API 連線，不要為 Bitpongo 開啟提領權限。\n\n建立策略，選擇幣種及比例，填寫每輪金額和執行時區。檢查可用餘額及交易所最小下單要求。逢低買入可不啟用。暫停只阻止之後的排程執行，不會撤銷已成交訂單。投資存在風險。',true,'2026-10-04 00:00:00'),
('alerts-not-orders','en-us','ARTICLE','Alerts are not trading orders','Price and sentiment alerts notify you; they do not place orders.','Price alerts, signed Webhook alerts and Fear & Greed subscriptions are independent from automatic buying plans. An alert may create an inbox entry and queue a push notification, but does not place an exchange order.\n\nA trading success notification should be checked against Trade history and exchange records. Neither sentiment indicators nor historical returns guarantee future results.',true,'2026-10-04 00:00:00'),
('alerts-not-orders','zh-cn','ARTICLE','提醒不等于交易订单','价格和情绪提醒只发送通知，不会下单。','价格提醒、签名 Webhook 提醒和恐惧贪婪指数订阅独立于自动买入策略。提醒可以产生收件箱消息并将推送入队，但不会向交易所下单。\n\n收到交易成功通知后，可在成交记录及交易所记录中核对。情绪指标和历史收益都不保证未来结果。',true,'2026-10-04 00:00:00'),
('alerts-not-orders','zh-tw','ARTICLE','提醒不等於交易訂單','價格和情緒提醒只傳送通知，不會下單。','價格提醒、簽名 Webhook 提醒和恐懼貪婪指數訂閱獨立於自動買入策略。提醒可以產生收件匣訊息並將推播入列，但不會向交易所下單。\n\n收到交易成功通知後，可在成交紀錄及交易所紀錄中核對。情緒指標和歷史收益都不保證未來結果。',true,'2026-10-04 00:00:00');
