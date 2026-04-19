package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.RefrigerationDataTools;
import org.example.agent.tool.WorkOrderTools;
import org.example.service.ChatSessionService.ChatSessionRecord;
import org.example.service.ChatSessionService.ChatMessageRecord;
import org.example.service.memory.UserPreferenceMemoryHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private RefrigerationDataTools refrigerationDataTools;

    @Autowired
    private WorkOrderTools workOrderTools;

    @Autowired
    private ObjectProvider<ToolCallbackProvider> toolCallbackProviders;

    @Autowired
    private BaseCheckpointSaver checkpointSaver;

    @Autowired
    private Store longTermMemoryStore;

    @Autowired
    private UserPreferenceMemoryHook userPreferenceMemoryHook;

    @Autowired
    private ChatSessionService chatSessionService;

    @Value("${agent.skills.project-directory:./.github/skills}")
    private String projectSkillsDirectory;

    @Autowired
    private DashScopeChatModel defaultDashScopeChatModel;

    @Value("${chat.long-context.enabled:true}")
    private boolean longContextEnabled;

    @Value("${chat.long-context.max-tokens-before-summary:12000}")
    private int maxTokensBeforeSummary;

    @Value("${chat.long-context.messages-to-keep:20}")
    private int messagesToKeep;

    @Value("${chat.long-context.keep-first-user-message:true}")
    private boolean keepFirstUserMessage;

    @Value("${chat.long-context.summary-prefix:## Previous conversation summary:}")
    private String summaryPrefix;

    @Value("${chat.long-context.summary-prompt:}")
    private String summaryPrompt;

    /**
     * 获取标准对话 ChatModel。
     *
     * 使用 starter 自动装配的 DashScopeChatModel（推荐写法），
     * 参数统一通过 application.yml 的 spring.ai.dashscope.chat.options 配置。
     */
    public DashScopeChatModel createStandardChatModel() {
        return defaultDashScopeChatModel;
    }

    /**
     * 构建系统提示词
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt() {
        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append("你是一个专业的智能助手。\n");
        systemPromptBuilder.append("请结合当前会话上下文与用户偏好记忆回答用户问题。\n");
        systemPromptBuilder.append("当你识别到用户表达偏好（例如语言风格、输出格式、表达习惯）时，应遵循这些偏好。\n");
        
        return systemPromptBuilder.toString();
    }

    public String resolveSessionId(String username, String requestSessionId) {
        ChatSessionRecord session = chatSessionService.resolveSession(username, requestSessionId);
        return session.getSessionId();
    }

    public ChatSessionRecord createNewSession(String username) {
        return chatSessionService.createNewSession(username);
    }

    public ChatSessionRecord getOrCreateCurrentSession(String username) {
        return chatSessionService.getOrCreateCurrentSession(username);
    }

    public Optional<ChatSessionRecord> getSession(String username, String sessionId) {
        return chatSessionService.getSession(username, sessionId);
    }

    public List<ChatSessionRecord> listSessions(String username) {
        return chatSessionService.listSessions(username);
    }

    public List<ChatMessageRecord> getSessionMessages(String username, String sessionId) {
        return chatSessionService.getMessages(username, sessionId);
    }

    public ChatSessionRecord recordUserMessage(String username, String sessionId, String content) {
        return chatSessionService.recordUserMessage(username, sessionId, content);
    }

    public ChatSessionRecord recordAssistantMessage(String username, String sessionId, String content) {
        return chatSessionService.recordAssistantMessage(username, sessionId, content);
    }

    public void deleteSession(String username, String sessionId) {
        chatSessionService.deleteSession(username, sessionId);
    }

    public int countUserMessages(String username, String sessionId) {
        return chatSessionService.countUserMessages(username, sessionId);
    }

    public RunnableConfig buildRunnableConfig(String username, String sessionId) {
        return RunnableConfig.builder()
                .threadId(buildThreadId(username, sessionId))
                .addMetadata("user_id", username)
                .store(longTermMemoryStore)
                .build();
    }

    public void clearShortTermMemory(String username, String sessionId) throws Exception {
        String normalizedSessionId = resolveSessionId(username, sessionId);
        RunnableConfig config = buildRunnableConfig(username, normalizedSessionId);
        checkpointSaver.release(config);
    }

    public Optional<Integer> getShortTermMessagePairCount(String username, String sessionId) {
        String normalizedSessionId = resolveSessionId(username, sessionId);
        RunnableConfig config = buildRunnableConfig(username, normalizedSessionId);
        Optional<Checkpoint> checkpointOptional = checkpointSaver.get(config);
        return checkpointOptional.map(this::extractMessagePairCount);
    }

    /**
     * 动态构建方法工具数组
     * 统一使用本地工具实现
     */
    public Object[] buildMethodToolsArray() {
        return new Object[]{dateTimeTools, internalDocsTools, workOrderTools};
    }

    private ToolCallbackProvider[] buildToolCallbackProviders() {
        return toolCallbackProviders.stream().toArray(ToolCallbackProvider[]::new);
    }

    private SkillsAgentHook buildSkillsAgentHook() {
        SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(projectSkillsDirectory)
                .build();

        ToolCallback[] refrigerationCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(refrigerationDataTools)
                .build()
                .getToolCallbacks();

        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .groupedTools(Map.of(
                        "refrigeration-data-processing",
                        Arrays.asList(refrigerationCallbacks)
                ))
                .build();
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        Hook[] hooks = buildAgentHooks(chatModel);

        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .toolCallbackProviders(buildToolCallbackProviders())
            .hooks(hooks)
                .saver(checkpointSaver)
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @param runnableConfig 运行时配置（线程、长期记忆存储、元数据）
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question, RunnableConfig runnableConfig) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question, runnableConfig);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }

    private Hook[] buildAgentHooks(DashScopeChatModel chatModel) {
        List<Hook> hooks = new ArrayList<>();
        hooks.add(buildSkillsAgentHook());
        hooks.add(userPreferenceMemoryHook);

        if (longContextEnabled) {
            hooks.add(buildSummarizationHook(chatModel));
        }

        return hooks.toArray(Hook[]::new);
    }

    private SummarizationHook buildSummarizationHook(DashScopeChatModel chatModel) {
        SummarizationHook.Builder builder = SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(Math.max(512, maxTokensBeforeSummary))
                .messagesToKeep(Math.max(4, messagesToKeep))
                .keepFirstUserMessage(keepFirstUserMessage);

        if (summaryPrefix != null && !summaryPrefix.isBlank()) {
            builder.summaryPrefix(summaryPrefix);
        }
        if (summaryPrompt != null && !summaryPrompt.isBlank()) {
            builder.summaryPrompt(summaryPrompt);
        }

        logger.debug("启用 Spring AI Alibaba SummarizationHook, maxTokensBeforeSummary={}, messagesToKeep={}",
                maxTokensBeforeSummary, messagesToKeep);
        return builder.build();
    }

    private String buildThreadId(String username, String sessionId) {
        return username + ":" + sessionId;
    }

    private int extractMessagePairCount(Checkpoint checkpoint) {
        if (checkpoint == null || checkpoint.getState() == null) {
            return 0;
        }

        Object messagesObj = checkpoint.getState().get("messages");
        if (!(messagesObj instanceof Collection<?> messages)) {
            return 0;
        }

        int userMessageCount = 0;
        for (Object obj : messages) {
            if (obj instanceof Message message && message.getMessageType() == MessageType.USER) {
                userMessageCount++;
            }
        }
        return userMessageCount;
    }
}
