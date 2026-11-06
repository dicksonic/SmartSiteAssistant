package com.reg.assistant.auth;

import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.PlatformAppMapper;
import com.reg.assistant.mapper.SessionMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.model.Entities.PlatformApp;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 免登Token服务：平台凭 appSecret 对 (appKey\ntimestamp\nuserName) 做 HMAC-SHA256 签名换Token。
 * 与Node版算法一致，同一平台凭证可跨两端互认。
 */
@Service
public class TokenService {

    private static final long TOKEN_TTL_HOURS = 2;
    private static final long TIMESTAMP_TOLERANCE_MS = 10 * 60 * 1000L;

    private final PlatformAppMapper platformAppMapper;
    private final SessionMapper sessionMapper;

    public TokenService(PlatformAppMapper platformAppMapper, SessionMapper sessionMapper) {
        this.platformAppMapper = platformAppMapper;
        this.sessionMapper = sessionMapper;
    }

    public static String sign(String appKey, String appSecret, long timestamp, String userName) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((appKey + "\n" + timestamp + "\n" + userName).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /** 生产入口：校验签名并签发会话Token */
    public AssistantSession issue(String appKey, String signature, long timestamp, String userName, String orgName) {
        PlatformApp app = platformAppMapper.findByAppKey(appKey);
        if (app == null) throw new ApiException(401, "无效的appKey");
        if (Math.abs(System.currentTimeMillis() - timestamp) > TIMESTAMP_TOLERANCE_MS) {
            throw new ApiException(401, "请求已过期（时间戳超出允许偏差）");
        }
        String expected = sign(appKey, app.getAppSecret(), timestamp, userName);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(401, "签名校验失败");
        }
        return createSession(app, userName, orgName);
    }

    /** 演示入口：仅 is_demo 平台允许服务端代签名 */
    public AssistantSession demoSign(String appKey, String userName, String orgName) {
        PlatformApp app = platformAppMapper.findByAppKey(appKey);
        if (app == null) throw new ApiException(404, "平台未注册");
        if (!Boolean.TRUE.equals(app.getIsDemo())) {
            throw new ApiException(403, "生产平台禁止服务端代签名：请由平台后端调用 issueAccessToken");
        }
        return createSession(app, userName, orgName);
    }

    /** 校验Token有效性，返回会话（含平台名） */
    public AssistantSession requireSession(String token) {
        AssistantSession session = sessionMapper.findByToken(token);
        if (session == null) throw new ApiException(401, "会话不存在，请从平台入口重新进入");
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(401, "会话已过期，请从平台入口重新进入");
        }
        return session;
    }

    private AssistantSession createSession(PlatformApp app, String userName, String orgName) {
        AssistantSession s = new AssistantSession();
        s.setToken(UUID.randomUUID().toString().replace("-", ""));
        s.setPlatformAppId(app.getId());
        s.setUserName(userName);
        s.setOrgName(orgName);
        s.setExpiresAt(LocalDateTime.now().plusHours(TOKEN_TTL_HOURS));
        sessionMapper.insert(s);
        s.setPlatformName(app.getPlatformName());
        return s;
    }
}
