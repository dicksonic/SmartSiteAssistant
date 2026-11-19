-- V3 M3 综合分析报告地基（MySQL 5.7 基线，Flyway 管理）
-- 国产库纪律同 V1：无反引号/无 unsigned/无 enum/无表达式默认值；JSON 一律 longtext，不用 json 类型
-- 注意：call_logs 的 CHECK 值域仍是 V1 口径。MySQL 5.7 静默忽略 CHECK，所以 scenario='report' 本机可插；
--       将来达梦/金仓强制 CHECK 时，必须把 call_logs.scenario 补 'report'，api_type 补 'agent'，列入 db/dm8 转换清单。

CREATE TABLE report_templates (
                                  id bigint AUTO_INCREMENT NOT NULL,
                                  name varchar(255) NOT NULL,
                                  source_file_name varchar(255),
                                  status varchar(16) NOT NULL DEFAULT 'draft',
                                  version int NOT NULL DEFAULT 1,
                                  spec_json longtext,
                                  created_by varchar(128),
                                  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  updated_at timestamp NULL,
                                  CONSTRAINT report_templates_id PRIMARY KEY (id),
                                  CONSTRAINT report_templates_status_check CHECK (status IN ('draft','confirmed','locked','deprecated'))
);

CREATE TABLE report_metrics (
                                id bigint AUTO_INCREMENT NOT NULL,
                                template_id bigint NOT NULL,
                                metric_code varchar(128) NOT NULL,
                                metric_name varchar(255) NOT NULL,
                                unit varchar(32),
                                origin varchar(16) NOT NULL DEFAULT 'custom',
                                status varchar(16) NOT NULL DEFAULT 'draft',
                                version int NOT NULL DEFAULT 1,
                                formula varchar(512),
                                rule_code varchar(128),
                                rule_version varchar(64),
                                spec_json longtext,
                                created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at timestamp NULL,
                                CONSTRAINT report_metrics_id PRIMARY KEY (id),
                                CONSTRAINT report_metrics_origin_check CHECK (origin IN ('extracted','ai-suggested','custom')),
                                CONSTRAINT report_metrics_status_check CHECK (status IN ('draft','confirmed','locked','deprecated')),
                                CONSTRAINT report_metrics_template_code_unique UNIQUE (template_id, metric_code, version)
);

CREATE TABLE report_charts (
                               id bigint AUTO_INCREMENT NOT NULL,
                               template_id bigint NOT NULL,
                               chart_code varchar(128) NOT NULL,
                               title varchar(255) NOT NULL,
                               metric_code varchar(128) NOT NULL,
                               chart_type varchar(32) NOT NULL,
                               spec_json longtext,
                               created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               CONSTRAINT report_charts_id PRIMARY KEY (id),
                               CONSTRAINT report_charts_type_check CHECK (chart_type IN ('bar','line','pie','table','stackbar')),
                               CONSTRAINT report_charts_template_code_unique UNIQUE (template_id, chart_code)
);

CREATE TABLE report_instances (
                                  id bigint AUTO_INCREMENT NOT NULL,
                                  template_id bigint,
                                  template_version int,
                                  status varchar(16) NOT NULL DEFAULT 'draft',
                                  params_json longtext,
                                  error text,
                                  pdf_path varchar(512),
                                  created_by varchar(128),
                                  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  confirmed_at timestamp NULL,
                                  exported_at timestamp NULL,
                                  CONSTRAINT report_instances_id PRIMARY KEY (id),
                                  CONSTRAINT report_instances_status_check CHECK (status IN ('draft','generating','preview','confirmed','exported','revised','failed'))
);

CREATE TABLE report_data_blocks (
                                    id bigint AUTO_INCREMENT NOT NULL,
                                    instance_id bigint NOT NULL,
                                    block_id varchar(64) NOT NULL,
                                    metric_code varchar(128) NOT NULL,
                                    columns_json longtext,
                                    rows_json longtext,
                                    meta_json longtext,
                                    hash varchar(128),
                                    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    CONSTRAINT report_data_blocks_id PRIMARY KEY (id),
                                    CONSTRAINT report_data_blocks_instance_block_unique UNIQUE (instance_id, block_id)
);

CREATE TABLE report_insights (
                                 id bigint AUTO_INCREMENT NOT NULL,
                                 instance_id bigint NOT NULL,
                                 insight_type varchar(32) NOT NULL,
                                 text text NOT NULL,
                                 refs_json longtext,
                                 severity varchar(16) NOT NULL DEFAULT 'info',
                                 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 CONSTRAINT report_insights_id PRIMARY KEY (id),
                                 CONSTRAINT report_insights_type_check CHECK (insight_type IN ('summary','threshold','rank','trend','missing')),
                                 CONSTRAINT report_insights_severity_check CHECK (severity IN ('info','warn','critical'))
);

ALTER TABLE report_metrics ADD CONSTRAINT report_metrics_template_fk
    FOREIGN KEY (template_id) REFERENCES report_templates (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE report_charts ADD CONSTRAINT report_charts_template_fk
    FOREIGN KEY (template_id) REFERENCES report_templates (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE report_instances ADD CONSTRAINT report_instances_template_fk
    FOREIGN KEY (template_id) REFERENCES report_templates (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE report_data_blocks ADD CONSTRAINT report_data_blocks_instance_fk
    FOREIGN KEY (instance_id) REFERENCES report_instances (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE report_insights ADD CONSTRAINT report_insights_instance_fk
    FOREIGN KEY (instance_id) REFERENCES report_instances (id) ON DELETE no action ON UPDATE no action;

CREATE INDEX report_metrics_template_idx ON report_metrics (template_id);
CREATE INDEX report_instances_template_idx ON report_instances (template_id);
CREATE INDEX report_instances_status_idx ON report_instances (status);
CREATE INDEX report_data_blocks_instance_idx ON report_data_blocks (instance_id);
CREATE INDEX report_insights_instance_idx ON report_insights (instance_id);