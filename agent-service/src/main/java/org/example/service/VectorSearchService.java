package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);
    private static final int HNSW_EF_SEARCH = 64;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MilvusClientV2 milvusClientV2;
    private final VectorEmbeddingService vectorEmbeddingService;
    private final RagRerankService ragRerankService;

    @Value("${rag.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${rag.hybrid.vector-candidate-multiplier:3}")
    private int vectorCandidateMultiplier;

    @Value("${rag.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${rag.rerank.candidate-multiplier:3}")
    private int rerankCandidateMultiplier;

    @Value("${milvus.database:default}")
    private String milvusDatabase;

    public VectorSearchService(MilvusClientV2 milvusClientV2,
                               VectorEmbeddingService vectorEmbeddingService,
                               RagRerankService ragRerankService) {
        this.milvusClientV2 = milvusClientV2;
        this.vectorEmbeddingService = vectorEmbeddingService;
        this.ragRerankService = ragRerankService;
    }

    /**
     * 搜索相似文档
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        try {
            logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);
            int safeTopK = Math.max(1, topK);
            int rerankCandidateTopK = resolveRerankCandidateTopK(safeTopK);

            if (!hybridEnabled) {
                List<SearchResult> vectorOnlyResults = searchByVector(query, rerankCandidateTopK);
                return ragRerankService.rerank(query, vectorOnlyResults, safeTopK);
            }

            int vectorTopK = Math.max(rerankCandidateTopK, safeTopK * Math.max(1, vectorCandidateMultiplier));
            CompletableFuture<List<SearchResult>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> searchByVector(query, vectorTopK))
                    .exceptionally(ex -> {
                        logger.warn("向量检索失败，降级为空结果: {}", ex.getMessage());
                        return Collections.emptyList();
                    });

            CompletableFuture<List<SearchResult>> bm25Future = CompletableFuture.supplyAsync(
                    () -> searchByBm25(query, rerankCandidateTopK))
                    .exceptionally(ex -> {
                        logger.warn("BM25 检索失败，降级为空结果: {}", ex.getMessage());
                        return Collections.emptyList();
                    });

            CompletableFuture.allOf(vectorFuture, bm25Future).join();

            List<SearchResult> vectorResults = vectorFuture.join();
            List<SearchResult> bm25Results = bm25Future.join();

            List<SearchResult> merged = fuseByRrf(vectorResults, bm25Results, rerankCandidateTopK);
            List<SearchResult> reranked = ragRerankService.rerank(query, merged, safeTopK);
            logger.info("并行检索完成, 向量结果: {}, BM25 结果: {}, 融合结果: {}",
                    vectorResults.size(), bm25Results.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            logger.error("并行检索失败，回退向量检索", e);
            try {
                int safeTopK = Math.max(1, topK);
                int rerankCandidateTopK = resolveRerankCandidateTopK(safeTopK);
                List<SearchResult> fallback = searchByVector(query, rerankCandidateTopK);
                return ragRerankService.rerank(query, fallback, safeTopK);
            } catch (Exception fallbackException) {
                logger.error("向量检索回退也失败", fallbackException);
                throw new RuntimeException("搜索失败: " + fallbackException.getMessage(), fallbackException);
            }
        }
    }

    private int resolveRerankCandidateTopK(int topK) {
        int safeTopK = Math.max(1, topK);
        return Math.max(safeTopK, safeTopK * Math.max(1, rerankCandidateMultiplier));
    }

    private List<SearchResult> searchByVector(String query, int topK) {
        int safeTopK = Math.max(1, topK);
        List<Float> queryVector = vectorEmbeddingService.generateQueryVector(query);

        SearchReq request = SearchReq.builder()
                .databaseName(milvusDatabase)
                .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .annsField("vector")
                .metricType(IndexParam.MetricType.L2)
                .limit(safeTopK)
                .outputFields(List.of("content", "metadata"))
                                .searchParams(Map.of("ef", HNSW_EF_SEARCH))
                .data(List.of(new FloatVec(queryVector)))
                .build();

        SearchResp response = milvusClientV2.search(request);
        return toSearchResults(response);
    }

    private List<SearchResult> searchByBm25(String query, int topK) {
        SearchReq request = SearchReq.builder()
                .databaseName(milvusDatabase)
                .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .annsField(MilvusConstants.BM25_SPARSE_FIELD_NAME)
                .metricType(IndexParam.MetricType.BM25)
                .limit(Math.max(1, topK))
                .outputFields(List.of("content", "metadata"))
                .data(List.of(new EmbeddedText(query)))
                .build();

        SearchResp response = milvusClientV2.search(request);
        return toSearchResults(response);
    }

    private List<SearchResult> toSearchResults(SearchResp response) {
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResp.SearchResult> firstQueryResults = response.getSearchResults().get(0);
        if (firstQueryResults == null || firstQueryResults.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResult> scored = new ArrayList<>(firstQueryResults.size());
        for (SearchResp.SearchResult item : firstQueryResults) {
            SearchResult result = new SearchResult();
            Object id = item.getId();
            result.setId(id == null ? item.getPrimaryKey() : String.valueOf(id));

            Map<String, Object> entity = item.getEntity();
            if (entity != null) {
                Object content = entity.get("content");
                Object metadata = entity.get("metadata");
                result.setContent(content == null ? "" : String.valueOf(content));
                if (metadata instanceof Map<?, ?> mapMetadata) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typedMetadata = (Map<String, Object>) mapMetadata;
                    result.setMetadata(toMetadataJson(typedMetadata));
                } else {
                    result.setMetadata(metadata == null ? null : safeToJson(metadata));
                }
            }

            Float score = item.getScore();
            result.setScore(score == null ? 0.0f : score);
            if (result.getId() == null || result.getId().isBlank()) {
                continue;
            }
            scored.add(result);
        }
        return scored;
    }

    private List<SearchResult> fuseByRrf(List<SearchResult> vectorResults, List<SearchResult> bm25Results, int topK) {
        if ((vectorResults == null || vectorResults.isEmpty()) && (bm25Results == null || bm25Results.isEmpty())) {
            return Collections.emptyList();
        }

        Map<String, SearchResult> mergedResultMap = new HashMap<>();
        Map<String, Double> rrfScoreMap = new HashMap<>();
        int effectiveRrfK = Math.max(1, rrfK);

        if (vectorResults != null) {
            for (int i = 0; i < vectorResults.size(); i++) {
                SearchResult result = vectorResults.get(i);
                if (result.getId() == null || result.getId().isBlank()) {
                    continue;
                }
                mergedResultMap.putIfAbsent(result.getId(), copyResult(result));
                rrfScoreMap.merge(result.getId(), 1.0d / (effectiveRrfK + i + 1), Double::sum);
            }
        }

        if (bm25Results != null) {
            for (int i = 0; i < bm25Results.size(); i++) {
                SearchResult result = bm25Results.get(i);
                if (result.getId() == null || result.getId().isBlank()) {
                    continue;
                }
                mergedResultMap.putIfAbsent(result.getId(), copyResult(result));
                rrfScoreMap.merge(result.getId(), 1.0d / (effectiveRrfK + i + 1), Double::sum);
            }
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(rrfScoreMap.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<SearchResult> results = new ArrayList<>();
        int limit = Math.max(1, topK);
        for (Map.Entry<String, Double> entry : ranked) {
            SearchResult base = mergedResultMap.get(entry.getKey());
            if (base == null) {
                continue;
            }
            base.setScore(entry.getValue().floatValue());
            results.add(base);
            if (results.size() >= limit) {
                break;
            }
        }

        return results;
    }

    private SearchResult copyResult(SearchResult source) {
        SearchResult copy = new SearchResult();
        copy.setId(source.getId());
        copy.setContent(source.getContent());
        copy.setMetadata(source.getMetadata());
        copy.setScore(source.getScore());
        return copy;
    }

    private String safeToJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String toMetadataJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            logger.warn("metadata 序列化失败，回退到 toString: {}", e.getMessage());
            return metadata.toString();
        }
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;

    }
}
