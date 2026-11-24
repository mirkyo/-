package com.ziwei.zw.config;

import lombok.RequiredArgsConstructor;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 模型工厂：按 {@code app.model.*} 手动创建对话 / 向量 / 多模态模型，懒加载单例。
 *
 * <p>为什么不直接声明成 {@code @Bean ChatModel}：agentx-core 把 openai / deepseek / zhipuai
 * 三个 starter 都带进来了，自动装配出来的模型 Bean 会互相争抢；这里统一手动创建，
 * 并在 yml 里把 {@code spring.ai.model.*} 设为 none。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelProvider {

    private final ZwProperties props;

    private volatile ChatModel chatModel;
    private final java.util.Map<String, ChatModel> chatModels = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile ChatModel multimodalModel;
    private volatile EmbeddingModel embeddingModel;

    /** 主对话模型（用于回答 + 技能/工具循环）。 */
    /**
     * 按档位 key 取对话模型。缓存按<b>真实模型名</b>去重——同一模型被多个档位共用时只建一个实例。
     */
    public ChatModel chatModel(String key) {
        ZwProperties.ModelOption opt = option(key);
        return chatModels.computeIfAbsent(opt.getModel(), model -> {
            ZwProperties.Chat c = props.getModel().getChat();
            ChatModel built = buildChatModel(c.getBaseUrl(), c.getApiKey(), model,
                    c.getTemperature(), c.getTimeoutSeconds());
            log.info("[model] 档位[{}] 显示名[{}] -> 模型 {}", opt.getKey(), opt.getLabel(), model);
            return built;
        });
    }

    /** 档位配置：key 命中不了就回落到第一个（默认 Opus 5.5） */
    public ZwProperties.ModelOption option(String key) {
        List<ZwProperties.ModelOption> list = props.getAssistant().getModels();
        if (list == null || list.isEmpty()) {
            return ZwProperties.Assistant.defaultModels().get(0);
        }
        for (ZwProperties.ModelOption o : list) {
            if (o.getKey() != null && o.getKey().equalsIgnoreCase(key)) {
                return o;
            }
        }
        return list.get(0);
    }

    public ChatModel chatModel() {
        ChatModel m = chatModel;
        if (m == null) {
            synchronized (this) {
                m = chatModel;
                if (m == null) {
                    ZwProperties.Chat c = props.getModel().getChat();
                    checkKey(c.getApiKey(), "chat");
                    m = buildChatModel(c.getBaseUrl(), c.getApiKey(), c.getModel(),
                            c.getTemperature(), c.getTimeoutSeconds());
                    chatModel = m;
                    log.info("[model] chat 模型就绪: {}/{}", c.getBaseUrl(), c.getModel());
                }
            }
        }
        return m;
    }

    /** 多模态模型（仅 MinerU 图片理解用，可空）。 */
    public ChatModel multimodalModel() {
        ChatModel m = multimodalModel;
        if (m == null) {
            synchronized (this) {
                m = multimodalModel;
                if (m == null) {
                    ZwProperties.Multimodal c = props.getModel().getMultimodal();
                    if (c.getApiKey() == null || c.getApiKey().isBlank()) {
                        return null;
                    }
                    m = buildChatModel(c.getBaseUrl(), c.getApiKey(), c.getModel(),
                            c.getTemperature(), 300);
                    multimodalModel = m;
                    log.info("[model] multimodal 模型就绪: {}/{}", c.getBaseUrl(), c.getModel());
                }
            }
        }
        return m;
    }

    /** 向量模型，维度必须与 PgVectorStore 的 dimensions 一致。 */
    public EmbeddingModel embeddingModel() {
        EmbeddingModel m = embeddingModel;
        if (m == null) {
            synchronized (this) {
                m = embeddingModel;
                if (m == null) {
                    ZwProperties.Embedding c = props.getModel().getEmbedding();
                    checkKey(c.getApiKey(), "embedding");
                    OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                            .model(c.getModel())
                            .dimensions(c.getDimensions())
                            .build();
                    m = new OpenAiEmbeddingModel(
                            buildApi(c.getBaseUrl(), c.getApiKey(), 60),
                            MetadataMode.EMBED,
                            options);
                    embeddingModel = m;
                    log.info("[model] embedding 模型就绪: {}/{} dim={}",
                            c.getBaseUrl(), c.getModel(), c.getDimensions());
                }
            }
        }
        return m;
    }

    /** 当前向量维度（PgVectorStore 建表要用）。 */
    public int embeddingDimensions() {
        return props.getModel().getEmbedding().getDimensions();
    }

    // ==================== 私有 ====================

    private ChatModel buildChatModel(String baseUrl, String apiKey, String model,
                                     Double temperature, int timeoutSeconds) {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(temperature);
        return OpenAiChatModel.builder()
                .openAiApi(buildApi(baseUrl, apiKey, timeoutSeconds))
                .defaultOptions(options)
                .build();
    }

    private OpenAiApi buildApi(String baseUrl, String apiKey, int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
    }

    private void checkKey(String apiKey, String which) {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("请替换")) {
            throw new IllegalStateException("请先在 application.yml 的 app.model." + which + ".api-key 填入模型密钥");
        }
    }
}
