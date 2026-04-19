package org.example.tool;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;

/**
 * 删除 Milvus Collection 的工具类
 * 用于重建 Collection 时清理旧数据
 */
public class DropCollection {
    
    public static void main(String[] args) {
        MilvusClientV2 client = null;
        
        try {
            // 连接到 Milvus
            System.out.println("正在连接到 Milvus localhost:19530...");
            client = new MilvusClientV2(
                    ConnectConfig.builder()
                            .uri("http://localhost:19530")
                            .dbName("default")
                            .build()
            );
            System.out.println("✓ 连接成功");
            
            String collectionName = "biz";
            
            // 检查 Collection 是否存在
            Boolean hasResponse = client.hasCollection(
                    HasCollectionReq.builder()
                            .databaseName("default")
                            .collectionName(collectionName)
                            .build()
            );
            
            if (Boolean.TRUE.equals(hasResponse)) {
                System.out.println("发现 Collection: " + collectionName);
                System.out.println("正在删除...");
                
                // 删除 Collection
                client.dropCollection(
                        DropCollectionReq.builder()
                                .databaseName("default")
                                .collectionName(collectionName)
                                .build()
                );

                System.out.println("✓ Collection 已成功删除");
                System.out.println("\n请重启 Spring Boot 应用，它会自动创建新的 FloatVector Collection");
            } else {
                System.out.println("Collection '" + collectionName + "' 不存在");
            }
            
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }
}
