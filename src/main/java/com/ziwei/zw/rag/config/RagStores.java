package com.ziwei.zw.rag.config;

import com.agentx.ai.rag.store.DocumentStore;
import com.agentx.ai.rag.store.JdbcDocumentStore;
import com.agentx.ai.rag.store.RagDocumentStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.ziwei.zw.config.ModelProvider;
import com.ziwei.zw.config.ZwProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * RAG 存储装配：PG 数据源（懒加载单例）+ 向量库 + 父块库 + 写入门面。
 *
 * <p>为什么不把这些声明成 {@code @Bean}：
 * 项目里已经有 MySQL 主数据源（Spring Boot 自动配置），再声明一个 {@code DataSource} Bean 会让
 * 主数据源回退。所以 PG 数据源作为一个私有单例持有，只在这里暴露。
 *
 * <p>向量表 {@code agentx_rag_vector} 与父块表 {@code agentx_rag_document} 都由框架自动建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagStores {

    private final ZwProperties props;
    private final ModelProvider modelProvider;
    private final DataSource dataSource;

    private volatile DataSource pgDataSource;
    private volatile VectorStore vectorStore;
    private volatile DocumentStore parentDocumentStore;

    /** PG 数据源（懒加载）。 */
    public DataSource pgDataSource() {
        DataSource ds = pgDataSource;
        if (ds == null) {
            synchronized (this) {
                ds = pgDataSource;
                if (ds == null) {
                    ZwProperties.Pgvector pg = props.getRag().getPgvector();
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s",
                            pg.getHost(), pg.getPort(), pg.getDatabase()));
                    config.setUsername(pg.getUsername());
                    config.setPassword(pg.getPassword());
                    config.setDriverClassName("org.postgresql.Driver");
                    config.setPoolName("ZwPgVectorHikariCP");
                    config.setMinimumIdle(2);
                    config.setMaximumPoolSize(10);
                    config.setConnectionTimeout(30_000);
                    ds = new HikariDataSource(config);
                    pgDataSource = ds;
                    log.info("[rag] PgVector 数据源就绪: {}", config.getJdbcUrl());
                }
            }
        }
        return ds;
    }

    public JdbcTemplate pgJdbcTemplate() {
        return new JdbcTemplate(pgDataSource());
    }

    /** 子块向量库（PgVectorStore 自动建表）。 */
    /**
     * 向量库（按 app.rag.vector-store 切换）。
     *
     * <p>pgvector：需要 PostgreSQL + vector 扩展；
     * qdrant：Windows / 无 Docker 场景用（起一个 qdrant.exe），这里用 gRPC 客户端连。
     */
    public VectorStore vectorStore() {
        VectorStore store = vectorStore;
        if (store != null) {
            return store;
        }
        synchronized (this) {
            store = vectorStore;
            if (store != null) {
                return store;
            }
            ZwProperties.Rag rag = props.getRag();
            if ("qdrant".equalsIgnoreCase(rag.getVectorStore())) {
                store = buildQdrant(rag.getQdrant());
            } else {
                store = buildPgvector(rag.getPgvector());
            }
            vectorStore = store;
            return store;
        }
    }

    private VectorStore buildQdrant(ZwProperties.Qdrant q) {
        String key = q.getApiKey() == null || q.getApiKey().isBlank() ? null : q.getApiKey();
        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(q.getHost(), q.getPort(), false).withApiKey(key).build());
        QdrantVectorStore built = QdrantVectorStore.builder(client, modelProvider.embeddingModel())
                .collectionName(q.getCollection())
                .initializeSchema(true)
                .build();
        try {
            built.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("QdrantVectorStore 初始化失败", e);
        }
        log.info("[rag] 向量库就绪（Qdrant）: {}:{} collection={} dim={}",
                q.getHost(), q.getPort(), q.getCollection(), modelProvider.embeddingDimensions());
        return built;
    }

    private VectorStore buildPgvector(ZwProperties.Pgvector pg) {
        PgVectorStore built = PgVectorStore.builder(pgJdbcTemplate(), modelProvider.embeddingModel())
                .dimensions(modelProvider.embeddingDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .removeExistingVectorStoreTable(false)
                .vectorTableName(pg.getVectorTable())
                .maxDocumentBatchSize(100)
                .build();
        try {
            built.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("PgVectorStore 初始化失败", e);
        }
        log.info("[rag] 向量表就绪: {} dim={}", pg.getVectorTable(), modelProvider.embeddingDimensions());
        return built;
    }

    /** 父块库：父块的完整小节原文（供父子回查）。 */
    public DocumentStore parentDocumentStore() {
        DocumentStore store = parentDocumentStore;
        if (store != null) {
            return store;
        }
        synchronized (this) {
            store = parentDocumentStore;
            if (store != null) {
                return store;
            }
            ZwProperties.Rag rag = props.getRag();
            if ("qdrant".equalsIgnoreCase(rag.getVectorStore())) {
                // 框架自带的 JdbcDocumentStore 是 PG 语法（ON CONFLICT），Windows 上落 MySQL 用自研实现
                store = new MysqlDocumentStore(dataSource, rag.getPgvector().getParentTable());
            } else {
                store = new JdbcDocumentStore(pgDataSource(), rag.getPgvector().getParentTable());
            }
            parentDocumentStore = store;
            return store;
        }
    }

    /** 写入门面：按 chunk 角色把父块/子块路由到不同存储。 */
    public RagDocumentStore documentStore() {
        return RagDocumentStore.builder()
                .vectorStore(vectorStore())
                .documentStore(parentDocumentStore())
                .build();
    }
}
