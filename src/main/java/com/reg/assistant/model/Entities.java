package com.reg.assistant.model;

import java.time.LocalDateTime;

/**
 * 实体集合（与 db/schema.sql 一一对应，下划线列名由 MyBatis 自动转驼峰）。
 * 业务记录表（qa_records/hazard_records/material_reviews/comparisons）的写入
 * 通过 RecordsMapper 以参数方式完成，不单独建实体类。
 */
public class Entities {

    /** 已建平台接入凭证 */
    public static class PlatformApp {
        private Long id;
        private String appKey;
        private String appSecret;
        private String platformName;
        private Boolean isDemo;
        private LocalDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getPlatformName() { return platformName; }
        public void setPlatformName(String platformName) { this.platformName = platformName; }
        public Boolean getIsDemo() { return isDemo; }
        public void setIsDemo(Boolean isDemo) { this.isDemo = isDemo; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    /** 免登会话 */
    public static class AssistantSession {
        private Long id;
        private String token;
        private Long platformAppId;
        private String userName;
        private String orgName;
        private LocalDateTime expiresAt;
        private LocalDateTime createdAt;
        // 关联查询带出（不入库）
        private String platformName;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public Long getPlatformAppId() { return platformAppId; }
        public void setPlatformAppId(Long platformAppId) { this.platformAppId = platformAppId; }
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public String getOrgName() { return orgName; }
        public void setOrgName(String orgName) { this.orgName = orgName; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public String getPlatformName() { return platformName; }
        public void setPlatformName(String platformName) { this.platformName = platformName; }
    }

    /** 规范条文知识库 */
    public static class KnowledgeItem {
        private Long id;
        private String category;
        private String title;
        private String content;
        private String keywords;
        private LocalDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getKeywords() { return keywords; }
        public void setKeywords(String keywords) { this.keywords = keywords; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    /** 隐患识别草稿：唯一建实体的业务记录——识别链路需 useGeneratedKeys 回填 id 供前端确认状态机使用 */
    public static class HazardDraft {
        private Long id;
        private Long sessionId;
        private String inputMode;
        private Boolean hasImage;
        private String transcript;
        private String category;
        private String location;
        private String description;
        private String rectification;
        private String basis;
        private String status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
        public String getInputMode() { return inputMode; }
        public void setInputMode(String inputMode) { this.inputMode = inputMode; }
        public Boolean getHasImage() { return hasImage; }
        public void setHasImage(Boolean hasImage) { this.hasImage = hasImage; }
        public String getTranscript() { return transcript; }
        public void setTranscript(String transcript) { this.transcript = transcript; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getRectification() { return rectification; }
        public void setRectification(String rectification) { this.rectification = rectification; }
        public String getBasis() { return basis; }
        public void setBasis(String basis) { this.basis = basis; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
