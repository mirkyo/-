package com.ziwei.zw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 平台配置（app.*）。模型 key 直接写 yml，不做数据库动态模型管理。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class ZwProperties {

    private Model model = new Model();
    private Assistant assistant = new Assistant();
    private Agent agent = new Agent();
    private Rag rag = new Rag();
    private Jwt jwt = new Jwt();
    private Init init = new Init();

    // ==================== 模型 ====================

    @Data
    public static class Model {
        private Chat chat = new Chat();
        private Embedding embedding = new Embedding();
        private Multimodal multimodal = new Multimodal();
    }

    @Data
    public static class Chat {
        private String baseUrl;
        private String apiKey;
        private String model = "qwen-plus";
        private Double temperature = 0.7;
        private int timeoutSeconds = 300;
    }

    @Data
    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        private String model = "text-embedding-v3";
        /** 必须与向量表维度一致 */
        private int dimensions = 1024;
    }

    @Data
    public static class Multimodal {
        private String baseUrl;
        private String apiKey;
        private String model = "qwen-vl-plus";
        private Double temperature = 0.2;
    }

    // ==================== 助手（平台只有一个，不再有智能体广场） ====================

    /**
     * 平台就一个助手。技能不再按智能体挑，**启用的技能全自动挂载**；
     * 知识库也不再「绑定」，检索时覆盖当前用户自己的全部知识库（可在前端按库收窄）。
     */
    @Data
    public static class Assistant {
        private String name = "知微助手";
        private String instructions = "";
        private boolean enableThinking = true;
        private boolean enableTodo = false;
        private boolean enableAskUser = true;
        /**
         * 模型档位列表：{@code key} 前端传、{@code label} 界面与人设显示名、{@code model} 真实模型名。
         * <p>当前全部指向 qwen3.7-flash；要换真模型只需改 model 字段。
         */
        private List<ModelOption> models = defaultModels();

        public static List<ModelOption> defaultModels() {
            return List.of(
                    opt("opus55", "Opus 5.5", "qwen3.7-flash"),
                    opt("sonnet55", "Sonnet 5.5", "qwen3.7-flash"),
                    opt("haiku55", "Haiku 5.5", "qwen3.7-flash"),
                    opt("gpt6astra", "GPT-6 Astra", "qwen3.7-flash"),
                    opt("gpt61sol", "GPT-6.1 Sol", "qwen3.7-flash"),
                    opt("gpt6luna", "GPT-6 Luna", "qwen3.7-flash"),
                    opt("ds41flash", "DeepSeek V4.1 Flash", "qwen3.7-flash"),
                    opt("ds4pro", "DeepSeek V4 Pro", "qwen3.7-flash"),
                    opt("glm53", "GLM-5.3", "qwen3.7-flash"),
                    opt("glm53flash", "GLM-5.3 Flash", "qwen3.7-flash"),
                    opt("kimik3", "Kimi K3", "qwen3.7-flash"),
                    opt("kimik26", "Kimi K2.6", "qwen3.7-flash"));
        }

        private static ModelOption opt(String key, String label, String model) {
            ModelOption o = new ModelOption();
            o.key = key; o.label = label; o.model = model;
            return o;
        }
    }

    /** 模型档位：key 给前端传，label 是界面显示名（= 人设自称），model 是真实模型名 */
    @Data
    public static class ModelOption {
        private String key;
        private String label;
        private String model;
    }

    // ==================== 智能体运行时（技能目录等） ====================

    @Data
    public static class Agent {
        /** 技能根目录：每个技能一个子目录，内含 SKILL.md */
        private String skillsDir = "./data/skills";
        private int defaultMaxRounds = 30;
        private boolean enableTrace = false;
        /** 无沙箱时本地工具组（bash / 文件 / grep）很危险，默认关闭 */
        private boolean allowLocalTools = false;
    }

    // ==================== RAG ====================

    @Data
    public static class Rag {
        /** 向量库类型：qdrant（默认）/ pgvector（已不推荐，仅留作兼容） */
        private String vectorStore = "qdrant";
        private Pgvector pgvector = new Pgvector();
        private Qdrant qdrant = new Qdrant();
        private Chunk chunk = new Chunk();
        private Upload upload = new Upload();
        private Mineru mineru = new Mineru();
        private QueryEnhance queryEnhance = new QueryEnhance();
        private Rerank rerank = new Rerank();
    }

    @Data
    public static class Pgvector {
        private String host = "localhost";
        private int port = 5433;
        private String database = "vector_store";
        private String username = "postgres";
        private String password = "";   // 由 PG_PASSWORD 环境变量注入
        /** 子块向量表（PgVectorStore 自动建） */
        private String vectorTable = "agentx_rag_vector";
        /** 父块表（JdbcDocumentStore 自动建） */
        private String parentTable = "agentx_rag_document";
    }

    @Data
    public static class Qdrant {
        private String host = "127.0.0.1";
        /** gRPC 端口（Qdrant 默认 6334，不是 REST 的 6333） */
        private int port = 6334;
        private String apiKey = "";
        private String collection = "ziwei_rag";
    }

    @Data
    public static class Chunk {
        private int chunkSize = 500;
        private int overlap = 50;
        /** 单文档最大切块数，超了直接拒绝，防 OOM */
        private int maxChunks = 20000;
        private int headingLevel = 2;
        private int maxParentSize = 5000;
    }

    @Data
    public static class Upload {
        private int maxFileSizeMb = 50;
    }

    @Data
    public static class Mineru {
        /** 留空 = 默认走 TIKA */
        private String token = "";
        /** 图片资产落盘目录（MinerU 解析出的图片存这里） */
        private String assetDir = "./data/assets";
    }

    /** 查询增强开关：照 aicube-robot 的实际取舍，默认全关 */
    @Data
    public static class QueryEnhance {
        private boolean compression = false;
        private boolean hyde = false;
        private boolean multiQuery = false;
    }

    @Data
    public static class Rerank {
        private boolean enabled = false;
        private String baseUrl;
        private String apiKey;
        private String model = "qwen3-rerank";
        private int topK = 3;
    }

    // ==================== 安全 / 初始化 ====================

    @Data
    public static class Jwt {
        private String secret;
        private int expireHours = 24;
    }

    @Data
    public static class Init {
        private String tenantName = "默认租户";
        private String tenantCode = "default";
        private String username = "admin";
        private String password = "";   // 由 INIT_ADMIN_PASSWORD 环境变量注入，不设默认值
    }
}
