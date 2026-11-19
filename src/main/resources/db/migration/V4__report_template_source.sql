-- V4 报告模板源文件与解析结果（MySQL 5.7 基线）
ALTER TABLE report_templates ADD COLUMN storage_path varchar(512);
ALTER TABLE report_templates ADD COLUMN parse_mode varchar(32);
ALTER TABLE report_templates ADD COLUMN block_count int NOT NULL DEFAULT 0;
ALTER TABLE report_templates ADD COLUMN parse_error text;
CREATE INDEX report_templates_status_idx ON report_templates (status);