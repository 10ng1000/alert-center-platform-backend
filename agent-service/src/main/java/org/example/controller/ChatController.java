package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.context.UserContextHolder;
import org.example.service.ChatService;
import org.example.service.ChatSessionService.ChatSessionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request,
                                                          @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        String username = getCurrentUsername(userIdHeader);
        try {
            String sessionId = chatService.resolveSessionId(username, request.getId());
            logger.info("收到对话请求 - User: {}, SessionId: {}, Question: {}", username, sessionId, request.getQuestion());

            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            chatService.recordUserMessage(username, sessionId, request.getQuestion());

            DashScopeChatModel chatModel = chatService.createStandardChatModel();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            String systemPrompt = chatService.buildSystemPrompt();
            
            ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);

            RunnableConfig runnableConfig = chatService.buildRunnableConfig(username, sessionId);
            UserContextHolder.setCurrentUser(username);
            
            String fullAnswer = chatService.executeChat(agent, request.getQuestion(), runnableConfig);
            chatService.recordAssistantMessage(username, sessionId, fullAnswer);

            ChatResponse response = ChatResponse.success(fullAnswer);
            response.setSessionId(sessionId);
            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request,
                                                                @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        try {
            String username = getCurrentUsername(userIdHeader);
            String sessionId = chatService.resolveSessionId(username, request.getId());
            logger.info("收到清空会话历史请求 - User: {}, SessionId: {}", username, sessionId);

            chatService.clearShortTermMemory(username, sessionId);
            return ResponseEntity.ok(ApiResponse.success("会话短期记忆已清空"));

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request,
                                 @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        SseEmitter emitter = new SseEmitter(300000L);
        String username = getCurrentUsername(userIdHeader);

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                String sessionId = chatService.resolveSessionId(username, request.getId());
                logger.info("收到 ReactAgent 对话请求 - User: {}, SessionId: {}, Question: {}", username, sessionId, request.getQuestion());

                chatService.recordUserMessage(username, sessionId, request.getQuestion());

                DashScopeChatModel chatModel = chatService.createStandardChatModel();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                String systemPrompt = chatService.buildSystemPrompt();
                
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);

                RunnableConfig runnableConfig = chatService.buildRunnableConfig(username, sessionId);
                UserContextHolder.setCurrentUser(username);

                emitter.send(SseEmitter.event()
                    .name("message")
                    .data(SseMessage.session(sessionId), MediaType.APPLICATION_JSON));
                
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                Flux<NodeOutput> stream = agent.stream(request.getQuestion(), runnableConfig);
                
                stream.subscribe(
                    output -> {
                        try {
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        UserContextHolder.clear();
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        UserContextHolder.clear();
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - User: {}, SessionId: {}, 答案长度: {}",
                                username, request.getId(), fullAnswer.length());

                            chatService.recordAssistantMessage(username, sessionId, fullAnswer);
                            
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                UserContextHolder.clear();
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId,
                                                                           @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        try {
            String username = getCurrentUsername(userIdHeader);
            logger.info("收到获取会话信息请求 - User: {}, SessionId: {}", username, sessionId);

            Optional<ChatSessionRecord> sessionRecord = chatService.getSession(username, sessionId);
            if (sessionRecord.isPresent()) {
                ChatSessionRecord record = sessionRecord.get();
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(record.getSessionId());
                response.setTitle(record.getTitle());
                response.setMessagePairCount(chatService.countUserMessages(username, sessionId));
                response.setCreateTime(record.getCreatedAt());
                response.setUpdateTime(record.getUpdatedAt());
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    private String getCurrentUsername(String userIdHeader) {
        if (!StringUtils.hasText(userIdHeader)) {
            return "anonymous";
        }
        return userIdHeader.trim();
    }

    @GetMapping("/chat/session/current")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getCurrentSession(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        try {
            String username = getCurrentUsername(userIdHeader);
            ChatSessionRecord record = chatService.getOrCreateCurrentSession(username);
            return ResponseEntity.ok(ApiResponse.success(buildSessionInfoResponse(username, record)));
        } catch (Exception e) {
            logger.error("获取当前会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/chat/session/new")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> createNewSession(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        try {
            String username = getCurrentUsername(userIdHeader);
            ChatSessionRecord record = chatService.createNewSession(username);
            return ResponseEntity.ok(ApiResponse.success(buildSessionInfoResponse(username, record)));
        } catch (Exception e) {
            logger.error("创建新会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    private SessionInfoResponse buildSessionInfoResponse(String username, ChatSessionRecord record) {
        SessionInfoResponse response = new SessionInfoResponse();
        response.setSessionId(record.getSessionId());
        response.setTitle(record.getTitle());
        response.setCreateTime(record.getCreatedAt());
        response.setUpdateTime(record.getUpdatedAt());
        response.setMessagePairCount(chatService.countUserMessages(username, record.getSessionId()));
        return response;
    }

    @GetMapping("/chat/sessions")
    public ResponseEntity<ApiResponse<List<SessionInfoResponse>>> listSessions(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        try {
            String username = getCurrentUsername(userIdHeader);
            List<SessionInfoResponse> responses = chatService.listSessions(username).stream()
                    .map(record -> buildSessionInfoResponse(username, record))
                    .toList();
            return ResponseEntity.ok(ApiResponse.success(responses));
        } catch (Exception e) {
            logger.error("获取会话列表失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/chat/session/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getSessionMessages(@PathVariable String sessionId,
                                                 @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        try {
            String username = getCurrentUsername(userIdHeader);
            List<ChatMessageResponse> messages = chatService.getSessionMessages(username, sessionId).stream()
                    .map(ChatMessageResponse::fromRecord)
                    .toList();
            return ResponseEntity.ok(ApiResponse.success(messages));
        } catch (Exception e) {
            logger.error("获取会话消息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<String>> deleteSession(@PathVariable String sessionId,
                                                             @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        try {
            String username = getCurrentUsername(userIdHeader);
            chatService.deleteSession(username, sessionId);
            return ResponseEntity.ok(ApiResponse.success("会话已删除"));
        } catch (Exception e) {
            logger.error("删除会话失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private String title;
        private int messagePairCount;
        private long createTime;
        private long updateTime;
    }

    @Setter
    @Getter
    public static class ChatMessageResponse {
        private String type;
        private String content;
        private long timestamp;

        public static ChatMessageResponse fromRecord(org.example.service.ChatSessionService.ChatMessageRecord record) {
            ChatMessageResponse response = new ChatMessageResponse();
            response.setType(record.getType());
            response.setContent(record.getContent());
            response.setTimestamp(record.getTimestamp());
            return response;
        }
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;
        private String sessionId;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;
        private String data;

        public static SseMessage session(String sessionId) {
            SseMessage message = new SseMessage();
            message.setType("session");
            message.setData(sessionId);
            return message;
        }

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
