-- V1 初始 schema（MySQL 基线，Flyway 管理）
-- 国产库预留纪律：本文件已规避 MySQL 私有语法（无反引号/无 unsigned/无 enum/无表达式默认值）
-- 将来达梦适配（db/dm8/ 脚本）只需转换三处：AUTO_INCREMENT→IDENTITY、boolean→BIT、timestamp→datetime 语义核对
-- MySQL 5.7 会静默忽略 CHECK 约束（仅解析不强制），8.0.16+ 与达梦/金仓均强制——应用层枚举纪律不受影响

CREATE TABLE platform_apps (
   id bigint AUTO_INCREMENT NOT NULL,
   app_key varchar(64) NOT NULL,
   app_secret varchar(128) NOT NULL,
   platform_name varchar(255) NOT NULL,
   is_demo boolean NOT NULL DEFAULT false,
   created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
   CONSTRAINT platform_apps_id PRIMARY KEY (id),
   CONSTRAINT platform_apps_app_key_unique UNIQUE (app_key)
);

CREATE TABLE assistant_sessions (
    id bigint AUTO_INCREMENT NOT NULL,
    token varchar(128) NOT NULL,
    platform_app_id bigint NOT NULL,
    user_name varchar(128) NOT NULL,
    org_name varchar(255),
    expires_at timestamp NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT assistant_sessions_id PRIMARY KEY (id),
    CONSTRAINT assistant_sessions_token_unique UNIQUE (token)
);

CREATE TABLE call_logs (
   id bigint AUTO_INCREMENT NOT NULL,
   session_id bigint,
   scenario varchar(16) NOT NULL,
   api_type varchar(16) NOT NULL,
   provider varchar(64) NOT NULL,
   model varchar(128) NOT NULL,
   usage_amount double NOT NULL,
   unit varchar(16) NOT NULL,
   latency_ms int NOT NULL,
   success boolean NOT NULL,
   created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
   CONSTRAINT call_logs_id PRIMARY KEY (id),
   CONSTRAINT call_logs_scenario_check CHECK (scenario IN ('qa','hazard','material','compare')),
   CONSTRAINT call_logs_api_type_check CHECK (api_type IN ('text','vision','asr','tts'))
);

CREATE TABLE comparisons (
     id bigint AUTO_INCREMENT NOT NULL,
     session_id bigint,
     before_text text NOT NULL,
     after_text text NOT NULL,
     has_photos boolean NOT NULL DEFAULT false,
     photo_sim_score double,
     text_consistent boolean NOT NULL,
     verdict varchar(16) NOT NULL,
     conclusion text NOT NULL,
     created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
     CONSTRAINT comparisons_id PRIMARY KEY (id),
     CONSTRAINT comparisons_verdict_check CHECK (verdict IN ('rectified','partial','not_rectified'))
);

CREATE TABLE hazard_records (
    id bigint AUTO_INCREMENT NOT NULL,
    session_id bigint,
    input_mode varchar(16) NOT NULL,
    has_image boolean NOT NULL DEFAULT false,
    transcript text,
    category varchar(128) NOT NULL,
    location varchar(255),
    description text NOT NULL,
    rectification text NOT NULL,
    basis varchar(512),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT hazard_records_id PRIMARY KEY (id),
    CONSTRAINT hazard_records_input_mode_check CHECK (input_mode IN ('photo','voice','text'))
);

CREATE TABLE knowledge_items (
     id bigint AUTO_INCREMENT NOT NULL,
     category varchar(128) NOT NULL,
     title varchar(255) NOT NULL,
     content text NOT NULL,
     keywords varchar(512),
     created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
     CONSTRAINT knowledge_items_id PRIMARY KEY (id)
);

CREATE TABLE material_reviews (
      id bigint AUTO_INCREMENT NOT NULL,
      session_id bigint,
      material_name varchar(255) NOT NULL,
      doc_type varchar(128) NOT NULL,
      total_pages int NOT NULL,
      missing_items text NOT NULL,
      logic_issues text NOT NULL,
      verdict varchar(16) NOT NULL,
      created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
      CONSTRAINT material_reviews_id PRIMARY KEY (id),
      CONSTRAINT material_reviews_verdict_check CHECK (verdict IN ('pass','conditional','reject'))
);

CREATE TABLE qa_records (
    id bigint AUTO_INCREMENT NOT NULL,
    session_id bigint,
    question text NOT NULL,
    answer text NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT qa_records_id PRIMARY KEY (id)
);

ALTER TABLE assistant_sessions ADD CONSTRAINT assistant_sessions_platform_app_id_platform_apps_id_fk
    FOREIGN KEY (platform_app_id) REFERENCES platform_apps (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE call_logs ADD CONSTRAINT call_logs_session_id_assistant_sessions_id_fk
    FOREIGN KEY (session_id) REFERENCES assistant_sessions (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE comparisons ADD CONSTRAINT comparisons_session_id_assistant_sessions_id_fk
    FOREIGN KEY (session_id) REFERENCES assistant_sessions (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE hazard_records ADD CONSTRAINT hazard_records_session_id_assistant_sessions_id_fk
    FOREIGN KEY (session_id) REFERENCES assistant_sessions (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE material_reviews ADD CONSTRAINT material_reviews_session_id_assistant_sessions_id_fk
    FOREIGN KEY (session_id) REFERENCES assistant_sessions (id) ON DELETE no action ON UPDATE no action;
ALTER TABLE qa_records ADD CONSTRAINT qa_records_session_id_assistant_sessions_id_fk
    FOREIGN KEY (session_id) REFERENCES assistant_sessions (id) ON DELETE no action ON UPDATE no action;

CREATE INDEX token_idx ON assistant_sessions (token);
CREATE INDEX scenario_idx ON call_logs (scenario);
CREATE INDEX created_idx ON call_logs (created_at);