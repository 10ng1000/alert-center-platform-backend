package org.example.service.memory;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 用户偏好长期记忆 Hook：
 * - beforeModel: 将长期偏好注入系统上下文。
 * - afterModel: 从用户输入中学习偏好并持久化。
 */
@Component
public class UserPreferenceMemoryHook extends ModelHook {

    private static final Logger logger = LoggerFactory.getLogger(UserPreferenceMemoryHook.class);

    private static final List<String> PREFERENCE_NAMESPACE = List.of("user_preferences");

    private static final String PREFERENCE_ITEMS_KEY = "items";

    private final Store memoryStore;

    public UserPreferenceMemoryHook(Store memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public String getName() {
        return "user_preference_memory_hook";
    }

    @Override
    public HookPosition[] getHookPositions() {
        return new HookPosition[]{HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL};
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        String userId = getUserId(config);
        if (userId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Optional<StoreItem> preferenceItem = memoryStore.getItem(PREFERENCE_NAMESPACE, userId);
        if (preferenceItem.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<String> preferences = loadPreferenceItems(preferenceItem.get().getValue());
        if (preferences.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> messages = getMessages(state);
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        String context = "已学习到的用户偏好：" + String.join("；", preferences);

        List<Message> newMessages = new ArrayList<>();
        newMessages.add(new SystemMessage(context));
        newMessages.addAll(messages);

        return CompletableFuture.completedFuture(Map.of("messages", newMessages));
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        String userId = getUserId(config);
        if (userId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> messages = getMessages(state);
        if (messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<String> extracted = extractPreferenceHints(messages);
        if (extracted.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Set<String> merged = new LinkedHashSet<>();
        memoryStore.getItem(PREFERENCE_NAMESPACE, userId)
                .ifPresent(item -> merged.addAll(loadPreferenceItems(item.getValue())));
        merged.addAll(extracted);

        StoreItem updated = StoreItem.of(
                PREFERENCE_NAMESPACE,
                userId,
                Map.of(
                        PREFERENCE_ITEMS_KEY, List.copyOf(merged),
                        "updatedAt", System.currentTimeMillis()
                )
        );
        memoryStore.putItem(updated);
        logger.info("用户偏好已更新 - userId: {}, count: {}", userId, merged.size());

        return CompletableFuture.completedFuture(Map.of());
    }

    private String getUserId(RunnableConfig config) {
        if (config == null) {
            return null;
        }

        return config.metadata("user_id")
                .map(String::valueOf)
                .filter(text -> !text.isBlank())
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Message> getMessages(OverAllState state) {
        if (state == null) {
            return List.of();
        }

        Object raw = state.value("messages").orElse(null);
        if (!(raw instanceof List<?> messageList)) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>();
        for (Object obj : messageList) {
            if (obj instanceof Message message) {
                messages.add(message);
            }
        }
        return messages;
    }

    private List<String> loadPreferenceItems(Map<String, Object> value) {
        if (value == null) {
            return List.of();
        }

        Object itemsObj = value.get(PREFERENCE_ITEMS_KEY);
        if (!(itemsObj instanceof List<?> list)) {
            return List.of();
        }

        List<String> items = new ArrayList<>();
        for (Object obj : list) {
            if (obj == null) {
                continue;
            }
            String text = String.valueOf(obj).trim();
            if (!text.isEmpty()) {
                items.add(text);
            }
        }
        return items;
    }

    private List<String> extractPreferenceHints(List<Message> messages) {
        List<String> extracted = new ArrayList<>();

        for (Message message : messages) {
            if (message.getMessageType() != MessageType.USER) {
                continue;
            }

            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            if (looksLikePreference(text)) {
                extracted.add(text.trim());
            }
        }

        return extracted.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private boolean looksLikePreference(String text) {
        String normalized = text.toLowerCase();
        return normalized.contains("喜欢")
                || normalized.contains("偏好")
                || normalized.contains("习惯")
                || normalized.contains("请用")
                || normalized.contains("尽量")
                || normalized.contains("不要")
                || normalized.contains("别用");
    }
}
