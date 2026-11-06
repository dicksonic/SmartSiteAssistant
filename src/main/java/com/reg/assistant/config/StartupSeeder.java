package com.reg.assistant.config;

import com.reg.assistant.mapper.PlatformAppMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动初始化（幂等）：首次启动写入演示平台凭证与规范条文知识库。
 * 表结构请先用 db/schema.sql 初始化（或接入 Flyway 管理）。
 */
@Component
public class StartupSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSeeder.class);

    private static final List<String[]> KNOWLEDGE_SEED = List.of(
            new String[]{"基坑工程", "《建筑基坑工程监测技术标准》GB50497 支护结构检查", "支护结构检查要点：排桩、地下连续墙应完整无破损；桩顶水平位移与沉降监测值不得超过设计预警值；冠梁、腰梁连接节点无开裂、松动。", "深基坑,支护,桩顶位移,监测"},
            new String[]{"基坑工程", "《建筑施工安全检查标准》JGJ59 基坑工程检查", "基坑工程检查要点：坑边2m范围内严禁堆载与停放机械；临边防护栏杆高度不低于1.2m并设警示标志；降水井运行正常，坑内外水位观测记录完整；上下通道设置符合方案。", "基坑,临边防护,堆载,降水"},
            new String[]{"脚手架工程", "《建筑施工扣件式钢管脚手架安全技术规范》JGJ130 架体检查", "脚手架检查要点：立杆基础平整夯实并设垫板；扫地杆、剪刀撑按方案设置；连墙件数量与位置符合两步三跨要求；扣件螺栓拧紧力矩40-65N·m；作业层脚手板满铺并设挡脚板。", "脚手架,连墙件,剪刀撑,扣件"},
            new String[]{"临时用电", "《施工现场临时用电安全技术规范》JGJ46 用电检查", "临时用电检查要点：实行三级配电两级保护；TN-S接零保护系统完整；配电箱开关箱实行一机一闸一漏一箱；漏电保护器动作电流不大于30mA、动作时间不大于0.1s；电缆架空或埋地敷设，严禁拖地泡水。", "临时用电,配电箱,漏电保护,三级配电"},
            new String[]{"高处作业", "《建筑施工高处作业安全技术规范》JGJ80 临边洞口检查", "高处作业检查要点：临边作业面设置1.2m高防护栏杆与挡脚板；预留洞口按尺寸采取盖板或防护栏杆并挂安全网；作业人员正确佩戴安全帽、系挂安全带且高挂低用。", "高处作业,临边,洞口,安全带"},
            new String[]{"起重机械", "《建筑施工塔式起重机安装使用拆卸安全技术规程》JGJ196 塔吊检查", "塔吊检查要点：力矩限制器、起重量限制器、高度限位等安全装置灵敏可靠；基础无积水、螺栓无松动；附墙装置间距与自由高度符合说明书；钢丝绳磨损断丝不超报废标准；持证上岗且交底记录齐全。", "塔吊,起重机械,限位,钢丝绳"}
    );

    private final PlatformAppMapper platformAppMapper;
    private final JdbcTemplate jdbc;

    public StartupSeeder(PlatformAppMapper platformAppMapper, JdbcTemplate jdbc) {
        this.platformAppMapper = platformAppMapper;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (platformAppMapper.count() > 0) return;
            jdbc.update("INSERT INTO platform_apps(app_key, app_secret, platform_name, is_demo) VALUES(?,?,?,?)",
                    "demo-platform", "demo-secret-change-in-production", "建设工程质量安全监管平台（演示）", true);
            for (String[] k : KNOWLEDGE_SEED) {
                jdbc.update("INSERT INTO knowledge_items(category, title, content, keywords) VALUES(?,?,?,?)",
                        k[0], k[1], k[2], k[3]);
            }
            log.info("[startup] 种子数据写入：演示平台 + {}条规范条文", KNOWLEDGE_SEED.size());
        } catch (Exception e) {
            log.error("[startup] 种子数据初始化失败（请确认已执行 db/schema.sql 建表）: {}", e.getMessage());
        }
    }
}
