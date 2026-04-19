package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

@Service
public class ChatSessionService {

    private static final String CURRENT_SESSION_KEY_PREFIX = "chat:session:current:";
    private static final String SESSION_META_KEY_PREFIX = "chat:session:meta:";
    private static final String SESSION_INDEX_KEY_PREFIX = "chat:session:index:";
    private static final String SESSION_MESSAGES_KEY_PREFIX = "chat:session:messages:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public ChatSessionRecord createNewSession(String username) {
        return createSession(username, UUID.randomUUID().toString(), "新对话", true);
    }

    public ChatSessionRecord getOrCreateCurrentSession(String username) {
        String currentSessionId = stringRedisTemplate.opsForValue().get(buildCurrentSessionKey(username));
        if (currentSessionId == null || currentSessionId.isBlank()) {
            return createNewSession(username);
        }
        return touchSession(username, currentSessionId);
    }

    public ChatSessionRecord resolveSession(String username, String requestedSessionId) {
        if (requestedSessionId == null || requestedSessionId.isBlank()) {
            return getOrCreateCurrentSession(username);
        }
        return touchSession(username, requestedSessionId);
    }

    public Optional<ChatSessionRecord> getSession(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        String raw = stringRedisTemplate.opsForValue().get(buildSessionMetaKey(username, sessionId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(raw, ChatSessionRecord.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read chat session from Redis", e);
        }
    }

    public ChatSessionRecord touchSession(String username, String sessionId) {
        return getSession(username, sessionId).map(session -> {
            session.setUpdatedAt(Instant.now().toEpochMilli());
            persistSession(username, session);
            markCurrentSession(username, sessionId);
            return session;
        }).orElseGet(() -> createSession(username, sessionId, "新对话", true));
    }

    public ChatSessionRecord recordUserMessage(String username, String sessionId, String content) {
        ChatSessionRecord session = resolveSession(username, sessionId);
        appendMessage(username, session.getSessionId(), "user", content);
        updateSessionTitleIfNeeded(username, session.getSessionId(), content);
        touchSession(username, session.getSessionId());
        return session;
    }

    public ChatSessionRecord recordAssistantMessage(String username, String sessionId, String content) {
        ChatSessionRecord session = resolveSession(username, sessionId);
        appendMessage(username, session.getSessionId(), "assistant", content);
        touchSession(username, session.getSessionId());
        return session;
    }

    public List<ChatSessionRecord> listSessions(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }

        Set<String> sessionIds = stringRedisTemplate.opsForZSet().reverseRange(buildSessionIndexKey(username), 0, -1);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }

        List<ChatSessionRecord> sessions = new ArrayList<>();
        for (String sessionId : sessionIds) {
            getSession(username, sessionId).ifPresent(sessions::add);
        }
        sessions.sort(Comparator.comparingLong(ChatSessionRecord::getUpdatedAt).reversed());
        return sessions;
    }

    public Optional<ChatSessionRecord> deleteSession(String username, String sessionId) {
        Optional<ChatSessionRecord> session = getSession(username, sessionId);
        if (session.isEmpty()) {
            return Optional.empty();
        }

        stringRedisTemplate.delete(buildSessionMetaKey(username, sessionId));
        stringRedisTemplate.delete(buildSessionMessagesKey(username, sessionId));
        stringRedisTemplate.opsForZSet().remove(buildSessionIndexKey(username), sessionId);

        String currentSessionId = stringRedisTemplate.opsForValue().get(buildCurrentSessionKey(username));
        if (sessionId.equals(currentSessionId)) {
            stringRedisTemplate.delete(buildCurrentSessionKey(username));
        }

        return session;
    }

    public List<ChatMessageRecord> getMessages(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        List<String> rawMessages = stringRedisTemplate.opsForList().range(buildSessionMessagesKey(username, sessionId), 0, -1);
        if (rawMessages == null || rawMessages.isEmpty()) {
            return List.of();
        }

        List<ChatMessageRecord> messages = new ArrayList<>();
        for (String raw : rawMessages) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                messages.add(objectMapper.readValue(raw, ChatMessageRecord.class));
            } catch (Exception e) {
                throw new RuntimeException("Failed to read chat message from Redis", e);
            }
        }
        return messages;
    }

    public int countUserMessages(String username, String sessionId) {
        return (int) getMessages(username, sessionId).stream()
                .filter(message -> "user".equals(message.getType()))
                .count();
    }

    public void markCurrentSession(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        stringRedisTemplate.opsForValue().set(buildCurrentSessionKey(username), sessionId);
    }

    private ChatSessionRecord createSession(String username, String sessionId, String title, boolean markCurrent) {
        ChatSessionRecord record = new ChatSessionRecord();
        long now = Instant.now().toEpochMilli();
        record.setSessionId(sessionId);
        record.setTitle(title);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        persistSession(username, record);
        indexSession(username, record);
        if (markCurrent) {
            markCurrentSession(username, sessionId);
        }
        return record;
    }

    private void persistSession(String username, ChatSessionRecord session) {
        try {
            stringRedisTemplate.opsForValue().set(
                    buildSessionMetaKey(username, session.getSessionId()),
                    objectMapper.writeValueAsString(session)
            );
            indexSession(username, session);
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist chat session to Redis", e);
        }
    }

    private void indexSession(String username, ChatSessionRecord session) {
        stringRedisTemplate.opsForZSet().add(buildSessionIndexKey(username), session.getSessionId(), session.getUpdatedAt());
    }

    private void appendMessage(String username, String sessionId, String type, String content) {
        ChatMessageRecord message = new ChatMessageRecord();
        message.setType(type);
        message.setContent(content);
        message.setTimestamp(Instant.now().toEpochMilli());
        try {
            stringRedisTemplate.opsForList().rightPush(buildSessionMessagesKey(username, sessionId), objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist chat message to Redis", e);
        }
    }

    private void updateSessionTitleIfNeeded(String username, String sessionId, String content) {
        ChatSessionRecord session = getSession(username, sessionId).orElseGet(() -> createSession(username, sessionId, "新对话", true));
        if (session.getTitle() != null && !session.getTitle().isBlank() && !"新对话".equals(session.getTitle())) {
            return;
        }

        session.setTitle(buildSessionTitle(content));
        session.setUpdatedAt(Instant.now().toEpochMilli());
        persistSession(username, session);
    }

    private String buildSessionTitle(String content) {
        if (content == null || content.isBlank()) {
            return "新对话";
        }
        String trimmed = content.trim();
        return trimmed.length() > 30 ? trimmed.substring(0, 30) + "..." : trimmed;
    }

    private String buildCurrentSessionKey(String username) {
        return CURRENT_SESSION_KEY_PREFIX + username;
    }

    private String buildSessionIndexKey(String username) {
        return SESSION_INDEX_KEY_PREFIX + username;
    }

    private String buildSessionMetaKey(String username, String sessionId) {
        return SESSION_META_KEY_PREFIX + username + ":" + sessionId;
    }

    private String buildSessionMessagesKey(String username, String sessionId) {
        return SESSION_MESSAGES_KEY_PREFIX + username + ":" + sessionId;
    }

    public static class ChatSessionRecord {
        private String sessionId;
        private String title;
        private long createdAt;
        private long updatedAt;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    public static class ChatMessageRecord {
        private String type;
        private String content;
        private long timestamp;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }
}