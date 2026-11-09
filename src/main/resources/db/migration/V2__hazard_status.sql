-- V2 隐患草稿人工确认状态机：pending_confirm → confirmed
-- 国产库纪律同 V1：无方言；MySQL 5.7 静默忽略 CHECK（仅解析），8.0.16+/达梦/金仓强制，应用层枚举纪律不受影响
ALTER TABLE hazard_records ADD COLUMN status varchar(16) NOT NULL DEFAULT 'pending_confirm';
ALTER TABLE hazard_records ADD CONSTRAINT hazard_records_status_check CHECK (status IN ('pending_confirm','confirmed'));
CREATE INDEX hazard_records_status_idx ON hazard_records (status);