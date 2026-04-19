package org.example.service.memory;

import com.alibaba.cloud.ai.graph.store.NamespaceListRequest;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.StoreSearchRequest;
import com.alibaba.cloud.ai.graph.store.StoreSearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Store 的 Redis 实现。
 * 以 JSON 形式保存 StoreItem，供长期记忆（用户偏好等）跨会话持久化。
 */
public class RedisLongTermStore implements Store {

    private static final String NAMESPACE_INDEX_SUFFIX = "idx:namespaces";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public RedisLongTermStore(StringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    @Override
    public void putItem(StoreItem item) {
        validateItem(item);
        String namespacePath = namespacePath(item.getNamespace());
        String key = buildItemKey(namespacePath, item.getKey());

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(item));
            redisTemplate.opsForSet().add(namespaceIndexKey(), namespacePath);
            redisTemplate.opsForSet().add(namespaceItemsKey(namespacePath), key);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize StoreItem", e);
        }
    }

    @Override
    public Optional<StoreItem> getItem(List<String> namespace, String key) {
        validateNamespaceAndKey(namespace, key);
        String raw = redisTemplate.opsForValue().get(buildItemKey(namespacePath(namespace), key));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(raw, StoreItem.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize StoreItem", e);
        }
    }

    @Override
    public boolean deleteItem(List<String> namespace, String key) {
        validateNamespaceAndKey(namespace, key);
        String namespacePath = namespacePath(namespace);
        String itemKey = buildItemKey(namespacePath, key);

        Boolean deleted = redisTemplate.delete(itemKey);
        redisTemplate.opsForSet().remove(namespaceItemsKey(namespacePath), itemKey);
        return Boolean.TRUE.equals(deleted);
    }

    @Override
    public StoreSearchResult searchItems(StoreSearchRequest request) {
        if (request == null) {
            return StoreSearchResult.empty();
        }

        List<StoreItem> candidates = loadCandidateItems(request.getNamespace());

        String query = request.getQuery();
        if (query != null && !query.isBlank()) {
            String lowered = query.toLowerCase();
            candidates = candidates.stream()
                    .filter(item -> item.toString().toLowerCase().contains(lowered))
                    .collect(Collectors.toList());
        }

        Map<String, Object> filter = request.getFilter();
        if (filter != null && !filter.isEmpty()) {
            candidates = candidates.stream()
                    .filter(item -> matchesFilter(item, filter))
                    .collect(Collectors.toList());
        }

        applySort(candidates, request.getSortFields(), request.isAscending());

        int offset = Math.max(request.getOffset(), 0);
        int limit = request.getLimit() > 0 ? request.getLimit() : 20;
        long total = candidates.size();

        int from = Math.min(offset, candidates.size());
        int to = Math.min(from + limit, candidates.size());
        List<StoreItem> pageItems = candidates.subList(from, to);

        return StoreSearchResult.of(pageItems, total, offset, limit);
    }

    @Override
    public List<String> listNamespaces(NamespaceListRequest request) {
        Set<String> namespaces = redisTemplate.opsForSet().members(namespaceIndexKey());
        if (namespaces == null || namespaces.isEmpty()) {
            return List.of();
        }

        List<String> list = new ArrayList<>(namespaces);
        if (request != null && request.getNamespace() != null && !request.getNamespace().isEmpty()) {
            String prefix = namespacePath(request.getNamespace());
            list = list.stream().filter(ns -> ns.startsWith(prefix)).collect(Collectors.toList());
        }

        if (request != null && request.getMaxDepth() > 0) {
            int maxDepth = request.getMaxDepth();
            list = list.stream()
                    .map(ns -> trimDepth(ns, maxDepth))
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .stream()
                    .collect(Collectors.toList());
        }

        int offset = request == null ? 0 : Math.max(request.getOffset(), 0);
        int limit = request == null || request.getLimit() <= 0 ? list.size() : request.getLimit();
        int from = Math.min(offset, list.size());
        int to = Math.min(from + limit, list.size());

        return list.subList(from, to);
    }

    @Override
    public void clear() {
        Set<String> keys = redisTemplate.keys(keyPrefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Override
    public long size() {
        return loadAllItemKeys().size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    private List<StoreItem> loadCandidateItems(List<String> namespace) {
        Set<String> keys;
        if (namespace == null || namespace.isEmpty()) {
            keys = loadAllItemKeys();
        } else {
            keys = redisTemplate.opsForSet().members(namespaceItemsKey(namespacePath(namespace)));
        }

        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<StoreItem> items = new ArrayList<>();
        for (String key : keys) {
            String raw = redisTemplate.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                items.add(objectMapper.readValue(raw, StoreItem.class));
            } catch (Exception ignore) {
                // Ignore broken single record; keep searching other records.
            }
        }

        return items;
    }

    private Set<String> loadAllItemKeys() {
        Set<String> namespaces = redisTemplate.opsForSet().members(namespaceIndexKey());
        if (namespaces == null || namespaces.isEmpty()) {
            return Set.of();
        }

        Set<String> keys = new HashSet<>();
        for (String namespace : namespaces) {
            Set<String> namespaceKeys = redisTemplate.opsForSet().members(namespaceItemsKey(namespace));
            if (namespaceKeys != null) {
                keys.addAll(namespaceKeys);
            }
        }
        return keys;
    }

    private boolean matchesFilter(StoreItem item, Map<String, Object> filter) {
        Map<String, Object> value = item.getValue();
        if (value == null) {
            return false;
        }

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if (!Objects.equals(value.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private void applySort(List<StoreItem> items, List<String> sortFields, boolean ascending) {
        if (items.isEmpty()) {
            return;
        }

        Comparator<StoreItem> comparator;
        if (sortFields == null || sortFields.isEmpty()) {
            comparator = Comparator.comparingLong(StoreItem::getUpdatedAt);
        } else {
            String field = sortFields.get(0);
            comparator = Comparator.comparing(item -> sortableValue(item.getValue(), field));
        }

        if (!ascending) {
            comparator = comparator.reversed();
        }
        items.sort(comparator);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Comparable sortableValue(Map<String, Object> value, String field) {
        if (value == null) {
            return "";
        }

        Object raw = value.get(field);
        if (raw instanceof Comparable comparable) {
            return comparable;
        }
        return raw == null ? "" : raw.toString();
    }

    private String trimDepth(String namespace, int maxDepth) {
        String[] parts = namespace.split(":");
        if (parts.length <= maxDepth) {
            return namespace;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < maxDepth; i++) {
            if (i > 0) {
                builder.append(":");
            }
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    private String buildItemKey(String namespacePath, String key) {
        return keyPrefix + namespacePath + "::" + key;
    }

    private String namespaceIndexKey() {
        return keyPrefix + NAMESPACE_INDEX_SUFFIX;
    }

    private String namespaceItemsKey(String namespacePath) {
        return keyPrefix + "idx:namespace:" + namespacePath;
    }

    private String namespacePath(List<String> namespace) {
        validateNamespace(namespace);
        return String.join(":", namespace);
    }

    private void validateItem(StoreItem item) {
        if (item == null) {
            throw new IllegalArgumentException("StoreItem cannot be null");
        }
        validateNamespaceAndKey(item.getNamespace(), item.getKey());
    }

    private void validateNamespaceAndKey(List<String> namespace, String key) {
        validateNamespace(namespace);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key cannot be blank");
        }
    }

    private void validateNamespace(List<String> namespace) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace cannot be empty");
        }
    }
}
