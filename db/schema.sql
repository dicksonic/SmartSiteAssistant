CREATE TABLE `assistant_sessions` (
	`id` bigint unsigned AUTO_INCREMENT NOT NULL,
	`token` varchar(128) NOT NULL,
	`platform_app_id` bigint unsigned NOT NULL,
	`user_name` varchar(128) NOT NULL,
	`org_name` varchar(255),
	`expires_at` timestamp NOT NULL,
	`created_at` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `assistant_sessions_id` PRIMARY KEY(`id`),
	CONSTRAINT `assistant_sessions_token_unique` UNIQUE(`token`)
);
--> statement-breakpoint
CREATE TABLE `call_logs` (
	`id` bigint unsigned AUTO_INCREMENT NOT NULL,
	`session_id` bigint unsigned,
	`scenario` enum('qa','hazard','material','compare') NOT NULL,
	`api_type` enum('text','vision','asr','tts') NOT NULL,
	`provider` varchar(64) NOT NULL,
	`model` varchar(128) NOT NULL,
	`usage_amount` double NOT NULL,
	`unit` varchar(16) NOT NULL,
	`latency_ms` int NOT NULL,
	`success` boolean NOT NULL,
	`created_at` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `call_logs_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `comparisons` (
	`id` bigint unsigned AUTO_INCREMENT NOT NULL,
	`session_id` bigint unsigned,
	`before_text` text NOT NULL,
	`after_text` text NOT NULL,
	`has_photos` boolean NOT NULL DEFAULT false,
	`photo_sim_score` double,
	`text_consistent` boolean NOT NULL,
	`verdict` enum('rectified','partial','not_rectified') NOT NULL,
	`conclusion` text NOT NULL,
	`created_at` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `comparisons_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `hazard_records` (
	`id` bigint unsigned AUTO_INCREMENT NOT NULL,
	`session_id` bigint unsigned,
	`input_mode` enum('photo','voice','text') NOT NULL,
	`has_image` boolean NOT NULL DEFAULT false,
	`transcript` text,
	`category` varchar(128) NOT NULL,
	`location` varchar(255),
	`description` text NOT NULL,
	`rectification` text NOT NULL,
	`basis` varchar(512),
	`created_at` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `hazard_records_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `knowledge_items` (
	`id` bigint unsigned AUTO_INCREMENT NOT NULL,
	`category` varchar(128) NOT NULL,
	`title` varchar(255) NOT NULL,
	`content` text NOT NULL,
	`keywords` varchar(512),
	`created_at` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `knowledge_items_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `material_reviews` (
	`id` bigint unsigned AUTO_INCREMENT NOT NULL,
	`session_id` bigint unsigned,
	`material_name` varchar(255) NOT NULL,
	`doc_type` varchar(128) NOT NULL,
	`total_pages` int NOT NULL,
	`missing_items` text NOT NULL,
	`logic_issues` text NOT NULL,
	`verdict` enum('pass','conditional','reject') NOT NULL,
	`created_at` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `material_reviews_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `platform_apps` (
	`id` bigint unsigned AUTO_INCREMENT NOT NULL,
	`app_key` varchar(64) NOT NULL,
	`app_secret` varchar(128) NOT NULL,
	`platform_name` varchar(255) NOT NULL,
	`is_demo` boolean NOT NULL DEFAULT false,
	`created_at` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `platform_apps_id` PRIMARY KEY(`id`),
	CONSTRAINT `platform_apps_app_key_unique` UNIQUE(`app_key`)
);
--> statement-breakpoint
CREATE TABLE `qa_records` (
	`id` bigint unsigned AUTO_INCREMENT NOT NULL,
	`session_id` bigint unsigned,
	`question` text NOT NULL,
	`answer` text NOT NULL,
	`created_at` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `qa_records_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
ALTER TABLE `assistant_sessions` ADD CONSTRAINT `assistant_sessions_platform_app_id_platform_apps_id_fk` FOREIGN KEY (`platform_app_id`) REFERENCES `platform_apps`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `call_logs` ADD CONSTRAINT `call_logs_session_id_assistant_sessions_id_fk` FOREIGN KEY (`session_id`) REFERENCES `assistant_sessions`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `comparisons` ADD CONSTRAINT `comparisons_session_id_assistant_sessions_id_fk` FOREIGN KEY (`session_id`) REFERENCES `assistant_sessions`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `hazard_records` ADD CONSTRAINT `hazard_records_session_id_assistant_sessions_id_fk` FOREIGN KEY (`session_id`) REFERENCES `assistant_sessions`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `material_reviews` ADD CONSTRAINT `material_reviews_session_id_assistant_sessions_id_fk` FOREIGN KEY (`session_id`) REFERENCES `assistant_sessions`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `qa_records` ADD CONSTRAINT `qa_records_session_id_assistant_sessions_id_fk` FOREIGN KEY (`session_id`) REFERENCES `assistant_sessions`(`id`) ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX `token_idx` ON `assistant_sessions` (`token`);--> statement-breakpoint
CREATE INDEX `scenario_idx` ON `call_logs` (`scenario`);--> statement-breakpoint
CREATE INDEX `created_idx` ON `call_logs` (`created_at`);