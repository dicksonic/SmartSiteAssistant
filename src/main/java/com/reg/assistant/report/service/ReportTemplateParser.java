package com.reg.assistant.report.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportTemplateParser {

    public record ParseResult(String parseMode, String rawText, List<Map<String, Object>> blocks, String error) {
    }

    public ParseResult parse(String ext, InputStream in) {
        try {
            if ("docx".equals(ext)) {
                return parseDocx(in);
            }
            if ("pdf".equals(ext)){
                return parsePdf(in);
            }
            return new ParseResult("rejected", "", List.of(), "仅支持 .docx/.pdf报告模板");
        } catch (Exception e) {
            return new ParseResult("error", "", List.of(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ParseResult parseDocx(InputStream in) throws Exception {
        List<Map<String, Object>> blocks = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String text = safe(p.getText());
                    if (text.isBlank()) continue;
                    Map<String, Object> b = block("paragraph", text);
                    String style = p.getStyle();
                    if (style != null && (style.toLowerCase().contains("heading") || style.contains("标题"))) {
                        b.put("kind", "heading");
                        b.put("style", style);
                    }
                    blocks.add(b);
                    raw.append(text).append('\n');
                } else if (el instanceof XWPFTable t) {
                    List<List<String>> rows = new ArrayList<>();
                    for (XWPFTableRow r : t.getRows()) {
                        List<String> row = new ArrayList<>();
                        for (XWPFTableCell c : r.getTableCells()) {
                            row.add(safe(c.getText()));
                        }
                        rows.add(row);
                    }
                    Map<String, Object> b = block("table", "");
                    b.put("rows", rows);
                    blocks.add(b);
                    raw.append("[table rows=").append(rows.size()).append("]\n");
                }
            }
        }
        return new ParseResult("docx-blocks", raw.toString(), blocks, null);
    }

    private ParseResult parsePdf(InputStream in) throws Exception {
        List<Map<String, Object>> blocks = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        try (PDDocument doc = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pages = doc.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = safe(stripper.getText(doc));
                Map<String, Object> page = block("page", "");
                page.put("pageNo", i);
                blocks.add(page);
                for (String para : pageText.split("\\R{2,}")) {
                    String p = para.trim();
                    if (p.isEmpty()) continue;
                    blocks.add(block("paragraph", p));
                    raw.append(p).append('\n');
                }
            }
            String text = raw.toString();
            if (text.trim().length() < 50) {
                // 典型扫描件/图片型 PDF：M3-1a 不接 OCR，明确标记，避免假装抽到了
                return new ParseResult("pdf-needs-ocr", text, blocks, "PDF 可抽取文本过少，疑似扫描件，需 OCR 后置处理");
            }
            return new ParseResult("pdf-text", text, blocks, null);
        }
    }

    private Map<String, Object> block(String type, String text) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", type);
        b.put("text", text);
        return b;
    }

    private String safe(String s) {
        return s == null ? "" : s.replace('\u0000', ' ').trim();
    }
}