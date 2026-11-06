package com.reg.assistant.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
