package org.example.client;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.index.request.DropIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.index.response.DescribeIndexResp;
import org.example.config.MilvusProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Milvus 客户端工厂类（v2）
 * 负责创建和初始化 Milvus v2 客户端连接
 */
@Component
public class MilvusClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(MilvusClientFactory.class);
    private static final String DENSE_VECTOR_FIELD_NAME = "vector";
    private static final String DENSE_VECTOR_INDEX_NAME = "vector_idx";
    private static final String SPARSE_BM25_INDEX_NAME = "sparse_bm25_idx";
    private static final int DENSE_HNSW_M = 16;
    private static final int DENSE_HNSW_EF_CONSTRUCTION = 200;

    @Autowired
    private MilvusProperties milvusProperties;

    /**
     * 创建并初始化 Milvus v2 客户端
     */
    public MilvusClientV2 createClientV2() {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(String.format("http://%s:%d", milvusProperties.getHost(), milvusProperties.getPort()))
                .dbName(milvusProperties.getDatabase())
                .connectTimeoutMs(milvusProperties.getTimeout())
                .rpcDeadlineMs(milvusProperties.getTimeout());

        if (milvusProperties.getUsername() != null && !milvusProperties.getUsername().isEmpty()) {
            builder.username(milvusProperties.getUsername())
                    .password(milvusProperties.getPassword());
        }

        MilvusClientV2 clientV2 = new MilvusClientV2(builder.build());

        try {
            logger.info("正在连接到 Milvus v2: {}:{}", milvusProperties.getHost(), milvusProperties.getPort());

            if (!collectionExists(clientV2, MilvusConstants.MILVUS_COLLECTION_NAME)) {
                logger.info("collection '{}' 不存在，正在创建...", MilvusConstants.MILVUS_COLLECTION_NAME);
                createBizCollectionWithBm25(clientV2);
                logger.info("成功创建 collection '{}'", MilvusConstants.MILVUS_COLLECTION_NAME);
            } else {
                int actualDim = getCollectionVectorDimension(clientV2, MilvusConstants.MILVUS_COLLECTION_NAME);
                boolean sparseReady = hasBm25SparseField(clientV2, MilvusConstants.MILVUS_COLLECTION_NAME);
                boolean functionReady = hasBm25Function(clientV2, MilvusConstants.MILVUS_COLLECTION_NAME);
                int expectedDim = milvusProperties.getVectorDim();
                if (actualDim != expectedDim || !sparseReady || !functionReady) {
                    logger.warn("collection '{}' 与 BM25 混合检索要求不匹配，维度: {}(期望 {}), sparse: {}, function: {}。将重建 collection。",
                            MilvusConstants.MILVUS_COLLECTION_NAME,
                            actualDim,
                            expectedDim,
                            sparseReady,
                            functionReady);
                    recreateBizCollection(clientV2);
                    logger.info("collection '{}' 已按新维度重建完成", MilvusConstants.MILVUS_COLLECTION_NAME);
                } else {
                    logger.info("collection '{}' 已存在，维度校验通过: {}", MilvusConstants.MILVUS_COLLECTION_NAME, actualDim);
                }
            }

            ensureCollectionIndexes(clientV2, MilvusConstants.MILVUS_COLLECTION_NAME);

            ensureCollectionLoaded(clientV2, MilvusConstants.MILVUS_COLLECTION_NAME);
            return clientV2;
        } catch (Exception e) {
            logger.error("创建 Milvus v2 客户端失败", e);
            clientV2.close();
            throw new RuntimeException("创建 Milvus v2 客户端失败: " + e.getMessage(), e);
        }
    }

    private boolean collectionExists(MilvusClientV2 client, String collectionName) {
        return Boolean.TRUE.equals(client.hasCollection(
                HasCollectionReq.builder()
                        .databaseName(milvusProperties.getDatabase())
                        .collectionName(collectionName)
                        .build()));
    }

    private DescribeCollectionResp describeCollection(MilvusClientV2 client, String collectionName) {
        return client.describeCollection(DescribeCollectionReq.builder()
                .databaseName(milvusProperties.getDatabase())
                .collectionName(collectionName)
                .build());
    }

    private int getCollectionVectorDimension(MilvusClientV2 client, String collectionName) {
        DescribeCollectionResp response = describeCollection(client, collectionName);
        CreateCollectionReq.CollectionSchema schema = response.getCollectionSchema();
        if (schema == null || schema.getFieldSchemaList() == null) {
            throw new RuntimeException("查询 collection schema 失败: " + collectionName);
        }

        for (CreateCollectionReq.FieldSchema field : schema.getFieldSchemaList()) {
            if (!DENSE_VECTOR_FIELD_NAME.equals(field.getName()) || field.getDataType() != DataType.FloatVector) {
                continue;
            }
            Integer dim = field.getDimension();
            if (dim == null) {
                throw new RuntimeException("未在 vector 字段找到 dim 参数");
            }
            return dim;
        }

        throw new RuntimeException("collection 中未找到 vector(FloatVector) 字段: " + collectionName);
    }

    private void recreateBizCollection(MilvusClientV2 client) {
        client.dropCollection(DropCollectionReq.builder()
                .databaseName(milvusProperties.getDatabase())
                .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .build());

        logger.warn("已删除旧 collection '{}', 准备按维度 {} 重建。",
                MilvusConstants.MILVUS_COLLECTION_NAME, milvusProperties.getVectorDim());

        createBizCollectionWithBm25(client);
    }

    private void createBizCollectionWithBm25(MilvusClientV2 clientV2) {
        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id")
                .dataType(DataType.VarChar)
                .maxLength(MilvusConstants.ID_MAX_LENGTH)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.FALSE)
                .build();

        CreateCollectionReq.FieldSchema denseVectorField = CreateCollectionReq.FieldSchema.builder()
                .name("vector")
                .dataType(DataType.FloatVector)
                .dimension(milvusProperties.getVectorDim())
                .build();

        CreateCollectionReq.FieldSchema contentField = CreateCollectionReq.FieldSchema.builder()
                .name("content")
                .dataType(DataType.VarChar)
                .maxLength(MilvusConstants.CONTENT_MAX_LENGTH)
                .enableAnalyzer(Boolean.TRUE)
                .enableMatch(Boolean.TRUE)
                .build();

        CreateCollectionReq.FieldSchema metadataField = CreateCollectionReq.FieldSchema.builder()
                .name("metadata")
                .dataType(DataType.JSON)
                .build();

        CreateCollectionReq.FieldSchema sparseField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.BM25_SPARSE_FIELD_NAME)
                .dataType(DataType.SparseFloatVector)
                .build();

        CreateCollectionReq.Function bm25Function = CreateCollectionReq.Function.builder()
                .name(MilvusConstants.BM25_FUNCTION_NAME)
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of("content"))
                .outputFieldNames(List.of(MilvusConstants.BM25_SPARSE_FIELD_NAME))
                .build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .fieldSchemaList(List.of(idField, denseVectorField, contentField, metadataField, sparseField))
                .functionList(List.of(bm25Function))
                .build();

        IndexParam denseIndex = buildDenseIndexParam();
        IndexParam sparseIndex = buildSparseBm25IndexParam();

        CreateCollectionReq createCollectionReq = CreateCollectionReq.builder()
                .databaseName(milvusProperties.getDatabase())
                .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .description("Business knowledge collection")
                .numShards(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .collectionSchema(schema)
                .indexParams(List.of(denseIndex, sparseIndex))
                .build();

        clientV2.createCollection(createCollectionReq);
    }

    private IndexParam buildDenseIndexParam() {
        Map<String, Object> denseIndexParams = new HashMap<>();
        denseIndexParams.put("M", DENSE_HNSW_M);
        denseIndexParams.put("efConstruction", DENSE_HNSW_EF_CONSTRUCTION);

        return IndexParam.builder()
                .fieldName(DENSE_VECTOR_FIELD_NAME)
                .indexName(DENSE_VECTOR_INDEX_NAME)
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.L2)
                .extraParams(denseIndexParams)
                .build();
    }

    private IndexParam buildSparseBm25IndexParam() {
        Map<String, Object> sparseIndexParams = new HashMap<>();
        sparseIndexParams.put("drop_ratio_build", 0.2d);

        return IndexParam.builder()
                .fieldName(MilvusConstants.BM25_SPARSE_FIELD_NAME)
                .indexName(SPARSE_BM25_INDEX_NAME)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .extraParams(sparseIndexParams)
                .build();
    }

    private void ensureCollectionIndexes(MilvusClientV2 client, String collectionName) {
        Set<String> indexNames = new HashSet<>(client.listIndexes(ListIndexesReq.builder()
                .databaseName(milvusProperties.getDatabase())
                .collectionName(collectionName)
                .build()));

        List<IndexParam> missingIndexes = new ArrayList<>();

        if (indexNames.contains(DENSE_VECTOR_INDEX_NAME) && !isDenseIndexHnsw(client, collectionName)) {
            logger.warn("collection '{}' 的索引 '{}' 不是 HNSW，将重建为 HNSW。", collectionName, DENSE_VECTOR_INDEX_NAME);
            releaseCollectionForIndexMaintenance(client, collectionName);
            client.dropIndex(DropIndexReq.builder()
                    .databaseName(milvusProperties.getDatabase())
                    .collectionName(collectionName)
                    .indexName(DENSE_VECTOR_INDEX_NAME)
                    .build());
            indexNames.remove(DENSE_VECTOR_INDEX_NAME);
        }

        if (!indexNames.contains(DENSE_VECTOR_INDEX_NAME)) {
            logger.warn("collection '{}' 缺少稠密向量索引 '{}', 将自动创建。", collectionName, DENSE_VECTOR_INDEX_NAME);
            missingIndexes.add(buildDenseIndexParam());
        }

        if (!indexNames.contains(SPARSE_BM25_INDEX_NAME)) {
            logger.warn("collection '{}' 缺少 BM25 稀疏索引 '{}', 将自动创建。", collectionName, SPARSE_BM25_INDEX_NAME);
            missingIndexes.add(buildSparseBm25IndexParam());
        }

        if (missingIndexes.isEmpty()) {
            logger.info("collection '{}' 索引检查通过，现有索引: {}", collectionName, indexNames);
            return;
        }

        client.createIndex(CreateIndexReq.builder()
                .databaseName(milvusProperties.getDatabase())
                .collectionName(collectionName)
                .indexParams(missingIndexes)
                .sync(Boolean.TRUE)
                .timeout(30_000L)
                .build());

        logger.info("collection '{}' 已补建索引: {}", collectionName,
                missingIndexes.stream().map(IndexParam::getIndexName).toList());
    }

    private void releaseCollectionForIndexMaintenance(MilvusClientV2 client, String collectionName) {
        client.releaseCollection(ReleaseCollectionReq.builder()
                .databaseName(milvusProperties.getDatabase())
                .collectionName(collectionName)
                .async(Boolean.FALSE)
                .timeout(30_000L)
                .build());
        logger.info("collection '{}' 已释放，用于索引维护", collectionName);
    }

    private boolean isDenseIndexHnsw(MilvusClientV2 client, String collectionName) {
        DescribeIndexResp describeIndexResp = client.describeIndex(DescribeIndexReq.builder()
                .databaseName(milvusProperties.getDatabase())
                .collectionName(collectionName)
                .indexName(DENSE_VECTOR_INDEX_NAME)
                .build());

        DescribeIndexResp.IndexDesc indexDesc = describeIndexResp.getIndexDescByIndexName(DENSE_VECTOR_INDEX_NAME);
        if (indexDesc == null || indexDesc.getIndexType() == null) {
            return false;
        }
        return indexDesc.getIndexType() == IndexParam.IndexType.HNSW;
    }

    private boolean hasBm25SparseField(MilvusClientV2 client, String collectionName) {
        DescribeCollectionResp response = describeCollection(client, collectionName);
        CreateCollectionReq.CollectionSchema schema = response.getCollectionSchema();

        if (schema == null || schema.getFieldSchemaList() == null) {
            return false;
        }

        for (CreateCollectionReq.FieldSchema field : schema.getFieldSchemaList()) {
            if (MilvusConstants.BM25_SPARSE_FIELD_NAME.equals(field.getName())
                    && field.getDataType() == DataType.SparseFloatVector) {
                return true;
            }
        }

        return false;
    }

    private boolean hasBm25Function(MilvusClientV2 client, String collectionName) {
        DescribeCollectionResp response = describeCollection(client, collectionName);
        CreateCollectionReq.CollectionSchema schema = response.getCollectionSchema();

        if (schema == null || schema.getFunctionList() == null) {
            return false;
        }

        for (CreateCollectionReq.Function functionSchema : schema.getFunctionList()) {
            if (!MilvusConstants.BM25_FUNCTION_NAME.equals(functionSchema.getName())) {
                continue;
            }
            if (functionSchema.getFunctionType() != FunctionType.BM25) {
                continue;
            }
            if (functionSchema.getInputFieldNames().contains("content")
                    && functionSchema.getOutputFieldNames().contains(MilvusConstants.BM25_SPARSE_FIELD_NAME)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 确保 collection 已加载，避免 delete/search 报 collection not loaded
     */
    private void ensureCollectionLoaded(MilvusClientV2 client, String collectionName) {
        client.loadCollection(LoadCollectionReq.builder()
                .databaseName(milvusProperties.getDatabase())
                .collectionName(collectionName)
                .sync(Boolean.TRUE)
                .timeout(30_000L)
                .build());

        logger.info("collection '{}' 已加载", collectionName);
    }
}
