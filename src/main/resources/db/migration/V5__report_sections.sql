-- V5 M3-4 报告长文章节表（MySQL 5.7 基线，Flyway 管理）
-- 国产库纪律同 V1/V3：无反引号/无 unsigned/无 enum/无表达式默认值；JSON 一律 longtext
-- 设计要点：
--   ① 章节是"一次生成的产物"，重新生成=整实例删除后重插（uk 防同节重复，不保留历史版本——
--      版本回溯靠 report_data_blocks.hash + D7 换出落盘，不靠章节表堆历史行）；
--   ② refs_json 记录本节引用的数据块 {blockId, metricCode, hash}，
--      hash 即 M3-3 的 SHA-256 内容指纹——M3-5"依据可回溯"的挂钩点。

CREATE TABLE report_sections (
                                 id bigint AUTO_INCREMENT NOT NULL,
                                 instance_id bigint NOT NULL,
                                 section_code varchar(64) NOT NULL,
                                 title varchar(255) NOT NULL,
                                 seq int NOT NULL,
                                 content longtext,
                                 refs_json longtext,
                                 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 updated_at timestamp NULL,
                                 CONSTRAINT report_sections_id PRIMARY KEY (id),
                                 CONSTRAINT report_sections_instance_section_unique UNIQUE (instance_id, section_code)
);

ALTER TABLE report_sections ADD CONSTRAINT report_sections_instance_fk
    FOREIGN KEY (instance_id) REFERENCES report_instances (id) ON DELETE no action ON UPDATE no action;

CREATE INDEX report_sections_instance_idx ON report_sections (instance_id);
