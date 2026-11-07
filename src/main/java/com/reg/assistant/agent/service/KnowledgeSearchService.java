package com.reg.assistant.agent.service;

import com.dicksonic.gaios.GaiosBootstrap;
import com.reg.assistant.mapper.KnowledgeMapper;
import com.reg.assistant.model.Entities.KnowledgeItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规范条文语义检索（M1-5）：E5 向量检索替代关键词 LIKE。
 * 形态选型：内存向量缓存（首次检索时全量 embed 进内存），不加向量列、不动表结构。
 * 取值依据：demo 种子 6 条、正式库预计几十~几百条，embed 全量 <1s，384 维 float 内存可忽略；
 * 失效条件：条文库上千条或高频变更时，演进为向量入库（届时出 Flyway V2 加 embeddings 列）。
 */
@Service
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);

    /** 全量加载上限：防库表异常膨胀拖垮启动期 embed。失效条件同上 */
    private static final int MAX_ITEMS = 200;

    private final KnowledgeMapper knowledgeMapper;
    private final ObjectProvider<GaiosBootstrap.Components> componentsProvider;

    /** 向量缓存：构建完成后整体替换引用，读路径无锁；null = 待（重）构建 */
    private volatile List<Entry> cache;

    private record Entry(KnowledgeItem item, float[] vector) {}
    private record Scored(KnowledgeItem item, double score) {}

    public KnowledgeSearchService(ObjectProvider<GaiosBootstrap.Components> componentsProvider, KnowledgeMapper knowledgeMapper) {
        this.componentsProvider = componentsProvider;
        this.knowledgeMapper = knowledgeMapper;
    }

    private GaiosBootstrap.Components components() {
        return componentsProvider.getObject();
    }

    /**
     * 语义检索 top-k。E5 任一环失败降级关键词 LIKE（运行期 fail-open：检索降级不挡问答）。
     * 向量已由 E5EmbeddingEngine 出口 L2 归一化，点积即余弦相似度。
     */
    public List<KnowledgeItem> search(String query, int limit) {
        try {
            List<Entry> entries = cache;
            if (entries == null) {
                entries = build();
            }
            float[] qv = components().e5().embed(query);
            List<Scored> scored = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                scored.add(new Scored(e.item(), dot(qv, e.vector())));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            List<Scored> top = scored.stream().limit(limit).toList();
            log.info("[KnowledgeSearch] 向量检索 q={} 命中：{}", abbrev(query),
                    top.stream().map(s -> s.item().getTitle() + "=" + String.format("%.3f", s.score())).toList());
            return top.stream().map(Scored::item).toList();
        } catch (Exception e) {
            log.warn("[KnowledgeSearch] 向量检索失败，降级关键词 LIKE：{}", e.getMessage());
            return keywordFallback(query, limit);
        }
    }

    /** 条文变更后调用：下次检索时重建缓存 */
    public void refresh() {
        cache = null;
    }

    /** 懒构建：首次检索时全量 embed（几十条 <1s）。double-checked，正常只构建一次 */
    private List<Entry> build() {
        synchronized (this) {
            if (cache != null) {
                return cache;
            }
            long start = System.currentTimeMillis();
            List<KnowledgeItem> all = knowledgeMapper.findAll(MAX_ITEMS);
            List<Entry> entries = new ArrayList<>(all.size());
            for (KnowledgeItem item : all) {
                // passage 端：标题+内容拼接，标题在前（E5 对文首更敏感，标题权重自然更高）
                entries.add(new Entry(item,
                        components().e5().embedPassage(item.getTitle() + "。" + item.getContent())));
            }
            cache = entries;
            log.info("[KnowledgeSearch] 向量缓存构建完成：{} 条，耗时 {}ms",
                    entries.size(), System.currentTimeMillis() - start);
            return entries;
        }
    }

    /** 降级路径：中文关键词 LIKE（与 M1-5 前 AssistantService.retrieveKnowledge 逻辑一致） */
    private List<KnowledgeItem> keywordFallback(String query, int limit) {
        List<String> keywords = new ArrayList<>();
        Matcher m = Pattern.compile("[\\u4e00-\\u9fa5]{2,}").matcher(query);
        while (m.find() && keywords.size() < 4) {
            keywords.add(m.group());
        }
        if (keywords.isEmpty()) {
            return knowledgeMapper.findAll(limit);
        }
        List<KnowledgeItem> found = knowledgeMapper.searchByKeywords(keywords, limit);
        return found.isEmpty() ? knowledgeMapper.findAll(limit) : found;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static String abbrev(String q) {
        return q.length() <= 20 ? q : q.substring(0, 20) + "…";
    }
}