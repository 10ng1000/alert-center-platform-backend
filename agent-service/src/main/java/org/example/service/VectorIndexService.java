package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HASH_CACHE_REDIS_KEY_PREFIX = "milvus:doc-hash:";
    private static final String EMBEDDING_SIGNATURE_REDIS_KEY_PREFIX = "milvus:embedding-signature:";

    private final MilvusClientV2 milvusClientV2;
    private final VectorEmbeddingService vectorEmbeddingService;
    private final DocumentChunkService chunkService;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${file.upload.path}")
    private String uploadPath;

    @Value("${milvus.database:default}")
    private String milvusDatabase;

    @Value("${dashscope.embedding.model}")
    private String embeddingModel;

    @Value("${dashscope.embedding.dimension:0}")
    private int embeddingDimension;

    public VectorIndexService(MilvusClientV2 milvusClientV2,
                              VectorEmbeddingService vectorEmbeddingService,
                              DocumentChunkService chunkService,
                              StringRedisTemplate stringRedisTemplate) {
        this.milvusClientV2 = milvusClientV2;
        this.vectorEmbeddingService = vectorEmbeddingService;
        this.chunkService = chunkService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostConstruct
    public void rebuildOnEmbeddingConfigChange() {
        String currentSignature = buildEmbeddingSignature();
        String previousSignature = getSavedEmbeddingSignature();

        if (currentSignature.equals(previousSignature)) {
            logger.info("Embedding 配置未变化，跳过自动重建。signature={}", currentSignature);
            return;
        }

        logger.warn("检测到 Embedding 配置变化，准备自动重建索引。old={}, new={}", previousSignature, currentSignature);

        clearHashCache();
        IndexingResult result = indexDirectory(null);

        if (result.isSuccess()) {
            saveEmbeddingSignature(currentSignature);
            logger.info("Embedding 配置变更后的自动重建完成。总数={}, 成功={}, 失败={}",
                    result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());
            return;
        }

        logger.warn("Embedding 配置变更后自动重建未完全成功，保留旧签名以便下次继续重试。错误信息={}", result.getErrorMessage());
    }

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;

            Path dirPath = resolveDirectoryPath(targetPath);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                logger.warn("索引目录不存在，已自动创建: {}", dirPath);
            }
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    private Path resolveDirectoryPath(String targetPath) {
        Path configuredPath = Paths.get(targetPath).normalize();
        if (configuredPath.isAbsolute()) {
            return configuredPath;
        }

        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path directPath = cwd.resolve(configuredPath).normalize();
        if (Files.exists(directPath)) {
            return directPath;
        }

        Path underModulePath = cwd.resolve("agent-service").resolve(configuredPath).normalize();
        if (Files.exists(underModulePath)) {
            return underModulePath;
        }

        Path parent = cwd.getParent();
        if (parent != null) {
            Path parentModulePath = parent.resolve("agent-service").resolve(configuredPath).normalize();
            if (Files.exists(parentModulePath)) {
                return parentModulePath;
            }
        }

        return directPath;
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        logger.info("开始索引文件: {}", path);

        // 1. 读取文件内容
        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        String normalizedPath = normalizeSourcePath(path);
        String currentHash = calculateContentHash(content);
        String oldHash = getCachedHash(normalizedPath);

        if (currentHash.equals(oldHash)) {
            logger.info("文件内容未变化，跳过 Milvus 更新: {}", normalizedPath);
            return;
        }

        // 2. 删除该文件的旧数据（如果存在）
        deleteExistingData(path.toString());

        // 3. 文档分片
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        // 4. 为每个分片生成向量并通过 Milvus v2 插入，BM25 sparse_vector 由 Milvus Function 自动生成
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            
            try {
                // 构建元数据（包含文件信息）
                Map<String, Object> metadata = buildMetadata(path.toString(), chunk, chunks.size(), currentHash);

                addToMilvus(chunk.getContent(), metadata, chunk.getChunkIndex());
                
                logger.info("✓ 分片 {}/{} 索引成功", i + 1, chunks.size());

            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 索引失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        saveCachedHash(normalizedPath, currentHash);

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    /**
     * 删除文件的旧数据（根据 metadata JSON 中的 _source）
     */
    private void deleteExistingData(String filePath) {
        try {
            // 使用统一的路径分隔符（正斜杠）用于Milvus存储，避免表达式解析错误
            // 将系统路径转换为统一格式
            Path path = Paths.get(filePath).normalize();
            String normalizedPath = path.toString().replace(File.separator, "/");
            
            // metadata 是 JSON 字段，过滤时需显式访问 JSON key
            String escapedPath = normalizedPath
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            String expr = String.format("metadata[\"_source\"] == \"%s\"", escapedPath);
            
            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            DeleteReq request = DeleteReq.builder()
                    .databaseName(milvusDatabase)
                    .collectionName(org.example.constant.MilvusConstants.MILVUS_COLLECTION_NAME)
                    .filter(expr)
                    .build();

            DeleteResp response = milvusClientV2.delete(request);
            if (response == null || response.getDeleteCnt() < 0) {
                throw new RuntimeException("Milvus v2 删除失败");
            }

            logger.info("✓ 已删除文件的旧数据: {}, 删除数量: {}", normalizedPath, response.getDeleteCnt());

        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
        }
    }

    /**
     * 构建元数据（包含文件信息）
     */
    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks, String contentHash) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = normalizeSourcePath(path);
        
        // 文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        metadata.put("contentHash", contentHash);
        
        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    private String normalizeSourcePath(Path path) {
        return path.normalize().toString().replace(File.separator, "/");
    }

    private String calculateContentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hash.append(String.format("%02x", b));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("无法计算文件哈希", e);
        }
    }

    private String getCachedHash(String normalizedPath) {
        try {
            Object cached = stringRedisTemplate.opsForHash().get(getHashRedisKey(), normalizedPath);
            return cached == null ? null : String.valueOf(cached);
        } catch (Exception e) {
            logger.warn("读取 Redis hash 缓存失败，继续执行重建: {}, 原因: {}", normalizedPath, e.getMessage());
            return null;
        }
    }

    private void saveCachedHash(String normalizedPath, String contentHash) {
        try {
            stringRedisTemplate.opsForHash().put(getHashRedisKey(), normalizedPath, contentHash);
        } catch (Exception e) {
            logger.warn("保存 Redis hash 缓存失败: {}, 原因: {}", normalizedPath, e.getMessage());
        }
    }

    private String getHashRedisKey() {
        return HASH_CACHE_REDIS_KEY_PREFIX + milvusDatabase;
    }

    private String getEmbeddingSignatureRedisKey() {
        return EMBEDDING_SIGNATURE_REDIS_KEY_PREFIX + milvusDatabase;
    }

    private String buildEmbeddingSignature() {
        String safeModel = embeddingModel == null ? "" : embeddingModel.trim();
        return safeModel + "|" + embeddingDimension;
    }

    private String getSavedEmbeddingSignature() {
        try {
            return stringRedisTemplate.opsForValue().get(getEmbeddingSignatureRedisKey());
        } catch (Exception e) {
            logger.warn("读取 Embedding 签名失败，按配置变更处理。原因: {}", e.getMessage());
            return null;
        }
    }

    private void saveEmbeddingSignature(String signature) {
        try {
            stringRedisTemplate.opsForValue().set(getEmbeddingSignatureRedisKey(), signature);
        } catch (Exception e) {
            logger.warn("保存 Embedding 签名失败: {}, 原因: {}", signature, e.getMessage());
        }
    }

    private void clearHashCache() {
        try {
            Boolean deleted = stringRedisTemplate.delete(getHashRedisKey());
            logger.info("已清理文档哈希缓存 key={}, deleted={}", getHashRedisKey(), deleted);
        } catch (Exception e) {
            logger.warn("清理文档哈希缓存失败: {}, 原因: {}", getHashRedisKey(), e.getMessage());
        }
    }

    /**
     * 插入向量到 Milvus
     */
    private void addToMilvus(String content,
                              Map<String, Object> metadata,
                              int chunkIndex) {
        try {
            // 生成唯一 ID（使用 _source + 分片索引）
            String source = (String) metadata.get("_source");
            String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();

            List<Float> embedding = vectorEmbeddingService.generateEmbedding(content);

            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("content", content);
            row.add("metadata", toJsonElement(metadata));
            row.add("vector", toJsonArray(embedding));

            InsertReq request = InsertReq.builder()
                    .databaseName(milvusDatabase)
                    .collectionName(org.example.constant.MilvusConstants.MILVUS_COLLECTION_NAME)
                    .data(Collections.singletonList(row))
                    .build();

            InsertResp response = milvusClientV2.insert(request);
            if (response == null || response.getInsertCnt() <= 0) {
                throw new RuntimeException("Milvus v2 插入失败，未返回插入计数");
            }

            logger.debug("向量插入成功: id={}, source={}, chunk={}", id, source, chunkIndex);

        } catch (Exception e) {
            logger.error("写入 Milvus 失败", e);
            throw new RuntimeException("写入 Milvus 失败: " + e.getMessage(), e);
        }
    }

    private com.google.gson.JsonElement toJsonElement(Map<String, Object> metadata) {
        try {
            return com.google.gson.JsonParser.parseString(OBJECT_MAPPER.writeValueAsString(metadata));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("metadata 序列化失败: " + e.getMessage(), e);
        }
    }

    private JsonArray toJsonArray(List<Float> embedding) {
        JsonArray array = new JsonArray();
        for (Float value : embedding) {
            array.add(value);
        }
        return array;
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
