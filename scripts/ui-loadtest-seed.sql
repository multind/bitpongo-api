-- Run ONLY against the isolated database created for this fixture.
-- No API keys, notification destinations or executable queue entries are copied.
SET @fixture_database = DATABASE();
SET NAMES utf8mb4;
CREATE TEMPORARY TABLE fixture_database_guard(name VARCHAR(64) NOT NULL, CHECK(name='bitpongo_ui_loadtest_20261030'));
INSERT INTO fixture_database_guard VALUES(DATABASE());
CREATE TEMPORARY TABLE fixture_sequence(n INT PRIMARY KEY);
INSERT INTO fixture_sequence WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<1000) SELECT n FROM seq;
START TRANSACTION;
INSERT INTO user(id,name,email,password,status,created_at) VALUES(1,'UI Load Test','ui-loadtest@example.invalid',NULL,'active',UTC_TIMESTAMP());
INSERT INTO exchange(id,name,exchange,access_key,secret_key,status,user_id,created_at)
SELECT n,CONCAT('Fixture exchange ',n),'binance',NULL,NULL,'inactive',1,UTC_TIMESTAMP() FROM fixture_sequence;
INSERT INTO strategy(id,name,instalment,exchange_id,frequency,cron,`condition`,user_id,created_at,schedule_timezone)
SELECT n,CONCAT('Fixture DCA ',n),10,n,'daily','0 0 8 * * ?','',1,UTC_TIMESTAMP(),'Asia/Shanghai' FROM fixture_sequence;
INSERT INTO plan(id,total_funds,total_revenue,total_ratio,next_time,status,user_id,triggered_count,created_at,strategy_id,exchange_id)
SELECT n,n*10,MOD(n,100)-50,MOD(n,20)-10,NULL,'stop',1,n,DATE_SUB(UTC_TIMESTAMP(),INTERVAL n HOUR),n,n FROM fixture_sequence;
INSERT INTO coin(id,proportion,icon,min,max,average_down,symbol,average,total_amount,income,user_id,created_at,plan_id)
SELECT n,'100','',0,0,0,'BTC',60000,0.001,0,1,UTC_TIMESTAMP(),n FROM fixture_sequence;
INSERT INTO `order`(id,symbol,order_no,client_order_id,total_amount,average_price,total_cost,fee,user_id,created_at,plan_id)
SELECT n,'BTC/USDT',CONCAT('fixture-',n),CONCAT('fixture-client-',n),0.001,60000,60,0.000001,1,DATE_SUB(UTC_TIMESTAMP(),INTERVAL n MINUTE),n FROM fixture_sequence;
INSERT INTO snapshot(id,value,type,user_id,created_at,plan_id)
SELECT n,CAST(1000+n AS CHAR),'asset',1,DATE_SUB(UTC_TIMESTAMP(),INTERVAL n HOUR),1 FROM fixture_sequence;
INSERT INTO price_alert(id,user_id,name,symbol,condition_type,target,frequency,candle_interval,expires_at,enabled,triggered_count,created_at)
SELECT n,1,CONCAT('Fixture alert ',n),'BTCUSDT','ABOVE',60000+n,'ONCE','1h',IF(MOD(n,4)=0,DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY),NULL),FALSE,MOD(n,10),DATE_SUB(UTC_TIMESTAMP(),INTERVAL n MINUTE) FROM fixture_sequence;
INSERT INTO alert_event(id,user_id,rule_id,event_type,event_key,title,body,symbol,price,read_at,occurred_at,inbox_deleted,log_deleted,confirmed_at)
SELECT n,1,n,'PRICE_ALERT_TRIGGERED',CONCAT('fixture-event-',n),
 LEFT(IF(MOD(n,10)=0,CONCAT('Long notification title ',REPEAT('BTC Ethereum 多语言提醒 ',3)),CONCAT('Price alert #',n)),100),
 IF(MOD(n,10)=0,CONCAT('Long notification body\n',REPEAT('BTCUSDT crossed the configured threshold. 多语言测试。 ',12)),CONCAT('BTCUSDT reached ',60000+n,' USDT.')),
 'BTCUSDT',60000+n,IF(MOD(n,3)=0,UTC_TIMESTAMP(),NULL),DATE_SUB(UTC_TIMESTAMP(),INTERVAL n MINUTE),FALSE,FALSE,IF(MOD(n,5)=0,UTC_TIMESTAMP(),NULL)
FROM fixture_sequence;
INSERT INTO fng_strategy(id,user_id,name,exchange,exchange_id,symbol,upper_limit_value,upper_limit_interval,lower_limit_value,lower_limit_interval,basic_amount,increase_ratio,leverage,bureau_period,direction,profit_target_percent,profit_drawdown_percent,profit_target_type,max_orders,max_positions,enabled,valid_from,created_at,updated_at)
SELECT n,1,CONCAT('Fixture FNG ',n),'binance',n,'BTCUSDT',75,5,25,5,10,1,1,1,'LONG',10,5,'PERCENT',5,5,FALSE,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP() FROM fixture_sequence;
INSERT INTO user_push_setting(user_id,enabled) VALUES(1,FALSE);
COMMIT;
SELECT 'price_alert',COUNT(*) FROM price_alert UNION ALL SELECT 'inbox/log shared events',COUNT(*) FROM alert_event UNION ALL SELECT 'plan',COUNT(*) FROM plan UNION ALL SELECT 'strategy',COUNT(*) FROM strategy UNION ALL SELECT 'orders',COUNT(*) FROM `order` UNION ALL SELECT 'fng_strategy',COUNT(*) FROM fng_strategy;
