package com.reg.assistant.mapper;

import com.reg.assistant.model.Entities;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface RecordsMapper {

    @Insert("INSERT INTO qa_records(session_id, question, answer) VALUES(#{sessionId}, #{question}, #{answer})")
    int insertQa(@Param("sessionId") Long sessionId, @Param("question") String question, @Param("answer") String answer);

    @Insert("INSERT INTO hazard_records(session_id, input_mode, has_image, transcript, category, location, description, rectification, basis) " +
            "VALUES(#{sessionId}, #{inputMode}, #{hasImage}, #{transcript}, #{category}, #{location}, #{description}, #{rectification}, #{basis})")
    int insertHazard(@Param("sessionId") Long sessionId, @Param("inputMode") String inputMode,
                     @Param("hasImage") boolean hasImage, @Param("transcript") String transcript,
                     @Param("category") String category, @Param("location") String location,
                     @Param("description") String description, @Param("rectification") String rectification,
                     @Param("basis") String basis);

    @Insert("INSERT INTO material_reviews(session_id, material_name, doc_type, total_pages, missing_items, logic_issues, verdict) " +
            "VALUES(#{sessionId}, #{materialName}, #{docType}, #{totalPages}, #{missingItems}, #{logicIssues}, #{verdict})")
    int insertMaterial(@Param("sessionId") Long sessionId, @Param("materialName") String materialName,
                       @Param("docType") String docType, @Param("totalPages") int totalPages,
                       @Param("missingItems") String missingItems, @Param("logicIssues") String logicIssues,
                       @Param("verdict") String verdict);

    @Insert("INSERT INTO comparisons(session_id, before_text, after_text, has_photos, photo_sim_score, text_consistent, verdict, conclusion) " +
            "VALUES(#{sessionId}, #{beforeText}, #{afterText}, #{hasPhotos}, #{photoSimScore}, #{textConsistent}, #{verdict}, #{conclusion})")
    int insertComparison(@Param("sessionId") Long sessionId, @Param("beforeText") String beforeText,
                         @Param("afterText") String afterText, @Param("hasPhotos") boolean hasPhotos,
                         @Param("photoSimScore") Double photoSimScore, @Param("textConsistent") boolean textConsistent,
                         @Param("verdict") String verdict, @Param("conclusion") String conclusion);

    /** 识别草稿入库：status 恒为 pending_confirm，id 由 useGeneratedKeys 回填到参数对象 */
    @Insert("INSERT INTO hazard_records(session_id, input_mode, has_image, transcript, category, location, description, rectification, basis, status) " +
            "VALUES(#{h.sessionId}, #{h.inputMode}, #{h.hasImage}, #{h.transcript}, #{h.category}, #{h.location}, #{h.description}, #{h.rectification}, #{h.basis}, #{h.status})")
    @Options(useGeneratedKeys = true, keyProperty = "h.id", keyColumn = "id")
    int insertHazardDraft(@Param("h") Entities.HazardDraft draft);

    /**
     * 人工确认：WHERE 带 status='pending_confirm' 把状态机守卫压进 SQL，
     * 重复确认/并发确认返回 0 行，由上层转成业务异常，绝不出确认态回退。
     * 确认时允许携带人工修正后的字段（识别草稿本来就该经人改一遍再入库）。
     */
    @Update("UPDATE hazard_records SET status='confirmed', category=#{category}, location=#{location}, " +
            "description=#{description}, rectification=#{rectification}, basis=#{basis} " +
            "WHERE id=#{id} AND status='pending_confirm'")
    int confirmHazard(@Param("id") long id, @Param("category") String category,
                      @Param("location") String location, @Param("description") String description,
                      @Param("rectification") String rectification, @Param("basis") String basis);

    /** M2-3：Agent 追问载入草稿上下文（主链确定性注入用） */
    @Select("SELECT id, session_id, input_mode, has_image, transcript, category, location, description, rectification, basis, status " +
            "FROM hazard_records WHERE id=#{id}")
    Entities.HazardDraft findHazardById(@Param("id") long id);

    /**
     * M2-3：HazardDraftTool 的台账查询。两个过滤条件都可空（=不过滤），
     * LIMIT 取值依据同 RegulationSearchTool：约束回喂模型的上下文体积。
     * LIMIT 子句在达梦/金仓（PG 兼容）同样支持，不算方言负债。
     */
    @Select("SELECT id, session_id, input_mode, has_image, transcript, category, location, description, rectification, basis, status " +
            "FROM hazard_records " +
            "WHERE (#{status} IS NULL OR status=#{status}) " +
            "AND (#{kw} IS NULL OR location LIKE CONCAT('%', #{kw}, '%')) " +
            "ORDER BY id DESC LIMIT #{limit}")
    List<Entities.HazardDraft> searchHazards(@Param("status") String status,
                                             @Param("kw") String kw,
                                             @Param("limit") int limit);
}
