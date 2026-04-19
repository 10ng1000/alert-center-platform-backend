package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分片服务
 * 负责将长文档切分为多个有语义完整性的小片段
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    @Autowired
    private DocumentChunkConfig chunkConfig;

    /**
     * 智能分片文档
     * 根据文件类型选择不同的分片策略
     * - txt 文件：使用 Spring AI 的 TokenTextSplitter
     * - md 文件：使用基于标题的智能分片
     * 
     * @param content 文档内容
     * @param filePath 文件路径（用于日志）
     * @return 文档分片列表
     */
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        String fileExtension = getFileExtension(filePath);
        
        // 如果是 txt 文件，使用 Spring AI TokenTextSplitter
        if ("txt".equalsIgnoreCase(fileExtension)) {
            return chunkWithSpringAiSplitter(content, filePath);
        }
        
        // 默认使用 Markdown 标题分片策略（适用于 md 文件）
        return chunkDocumentByHeadings(content, filePath);
    }

    /**
     * 使用 Spring AI 的 TokenTextSplitter 进行分片
     * 适用于 txt 等纯文本文件
     * 
     * @param content 文档内容
     * @param filePath 文件路径
     * @return 文档分片列表
     */
    private List<DocumentChunk> chunkWithSpringAiSplitter(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空：{}", filePath);
            return chunks;
        }

        try {
            // 创建 Spring AI 的 Document
            Document document = new Document(content);
            
            // 使用 builder 风格配置，更符合 Spring AI 1.1.x 推荐写法
            TextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkConfig.getMaxSize()) // 每个分片的目标 token 数
                .withMinChunkSizeChars(350)              // 最小分片字符数
                .withMinChunkLengthToEmbed(5)            // 最小分片长度
                .withMaxNumChunks(10000)                 // 最大分片数量
                .withKeepSeparator(true)                 // 保持分隔符
                .build();

            List<Document> splitDocuments = splitter.split(document);
            
            int chunkIndex = 0;
            for (Document doc : splitDocuments) {
                String docContent = doc.getText();
                DocumentChunk chunk = new DocumentChunk(
                    docContent,
                    0,  // 对于 Spring AI 分片，不使用精确的字符索引
                    docContent.length(),
                    chunkIndex++
                );
                chunks.add(chunk);
            }
            
            logger.info("使用 Spring AI TokenTextSplitter 分片完成：{} -> {} 个分片", filePath, chunks.size());
            
        } catch (Exception e) {
            logger.error("使用 Spring AI 分片器失败，回退到默认分片策略：{}", filePath, e);
            // 如果 Spring AI 分片失败，回退到默认的分片策略
            return chunkDocumentByHeadings(content, filePath);
        }
        
        return chunks;
    }

    /**
     * 智能分片文档（按标题分割）
     * 优先按照标题、段落边界进行分割，保持语义完整性
     * 适用于 Markdown 格式文件
     * 
     * @param content 文档内容
     * @param filePath 文件路径（用于日志）
     * @return 文档分片列表
     */
    private List<DocumentChunk> chunkDocumentByHeadings(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        // 1. 首先尝试按标题分割（Markdown格式）
        List<Section> sections = splitByHeadings(content);
        
        // 2. 对每个章节进行进一步分片
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, globalChunkIndex);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }

        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }

    /**
     * 按照 Markdown 标题分割文档
     */
    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        
        // 匹配 Markdown 标题：# 标题, ## 标题, ### 标题等
        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(content);

        int lastEnd = 0;
        String currentTitle = null;

        while (matcher.find()) {
            // 保存上一个章节
            if (lastEnd < matcher.start()) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    sections.add(new Section(currentTitle, sectionContent, lastEnd));
                }
            }

            // 更新当前标题
            currentTitle = matcher.group(2).trim();
            lastEnd = matcher.start();
        }

        // 添加最后一个章节
        if (lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            if (!sectionContent.isEmpty()) {
                sections.add(new Section(currentTitle, sectionContent, lastEnd));
            }
        }

        // 如果没有找到任何标题，将整个文档作为一个章节
        if (sections.isEmpty()) {
            sections.add(new Section(null, content, 0));
        }

        return sections;
    }

    /**
     * 对单个章节进行分片
     */
    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = section.content;
        String title = section.title;

        // 如果章节内容小于最大尺寸，直接作为一个分片
        if (content.length() <= chunkConfig.getMaxSize()) {
            DocumentChunk chunk = new DocumentChunk(
                content, 
                section.startIndex, 
                section.startIndex + content.length(), 
                startChunkIndex
            );
            chunk.setTitle(title);
            chunks.add(chunk);
            return chunks;
        }

        // 章节内容较长，需要进一步分片
        // 优先在段落边界分割
        List<String> paragraphs = splitByParagraphs(content);
        
        StringBuilder currentChunk = new StringBuilder();
        int currentStartIndex = section.startIndex;
        int chunkIndex = startChunkIndex;

        for (String paragraph : paragraphs) {
            // 如果当前分片加上新段落超过最大尺寸
            if (currentChunk.length() > 0 && 
                currentChunk.length() + paragraph.length() > chunkConfig.getMaxSize()) {
                
                // 保存当前分片
                String chunkContent = currentChunk.toString().trim();
                DocumentChunk chunk = new DocumentChunk(
                    chunkContent,
                    currentStartIndex,
                    currentStartIndex + chunkContent.length(),
                    chunkIndex++
                );
                chunk.setTitle(title);
                chunks.add(chunk);

                // 开始新分片，包含重叠部分
                String overlap = getOverlapText(chunkContent);
                currentChunk = new StringBuilder(overlap);
                currentStartIndex = currentStartIndex + chunkContent.length() - overlap.length();
            }

            currentChunk.append(paragraph).append("\n\n");
        }

        // 保存最后一个分片
        if (currentChunk.length() > 0) {
            String chunkContent = currentChunk.toString().trim();
            DocumentChunk chunk = new DocumentChunk(
                chunkContent,
                currentStartIndex,
                currentStartIndex + chunkContent.length(),
                chunkIndex
            );
            chunk.setTitle(title);
            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * 按段落分割文本
     */
    private List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        
        // 按双换行符分割段落
        String[] parts = content.split("\n\n+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }

        return paragraphs;
    }

    /**
     * 获取重叠文本
     * 从文本末尾提取指定长度的内容作为下一个分片的开头
     */
    private String getOverlapText(String text) {
        int overlapSize = Math.min(chunkConfig.getOverlap(), text.length());
        if (overlapSize <= 0) {
            return "";
        }

        // 从末尾提取重叠内容
        String overlap = text.substring(text.length() - overlapSize);
        
        // 尝试在句子边界截断（查找最后一个句号、问号、感叹号）
        int lastSentenceEnd = Math.max(
            overlap.lastIndexOf('。'),
            Math.max(overlap.lastIndexOf('？'), overlap.lastIndexOf('！'))
        );
        
        if (lastSentenceEnd > overlapSize / 2) {
            return overlap.substring(lastSentenceEnd + 1).trim();
        }

        return overlap.trim();
    }

    /**
     * 章节数据类
     */
    private static class Section {
        String title;
        String content;
        int startIndex;

        Section(String title, String content, int startIndex) {
            this.title = title;
            this.content = content;
            this.startIndex = startIndex;
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }
        int lastIndexOf = filePath.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filePath.substring(lastIndexOf + 1).toLowerCase();
    }
}
