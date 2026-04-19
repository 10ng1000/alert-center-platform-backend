package org.example.controller;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.ListCollectionsReq;
import io.milvus.v2.service.collection.response.ListCollectionsResp;
import org.example.config.MilvusProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Milvus 测试控制器
 * 用于测试数据库连接和数据读取
 */
@RestController
@RequestMapping("/milvus")
public class MilvusCheckController {

    @Autowired
    private MilvusClientV2 milvusClientV2;

    @Autowired
    private MilvusProperties milvusProperties;

    /**
     * 简单的健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> simpleHealth() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            ListCollectionsResp response = milvusClientV2.listCollectionsV2(ListCollectionsReq.builder()
                    .databaseName(milvusProperties.getDatabase())
                    .build());

            result.put("message", "ok");
            result.put("collections", response.getCollectionNames());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.status(503).body(result);
        }
    }
}
