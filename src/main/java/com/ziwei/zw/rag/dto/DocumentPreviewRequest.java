package com.ziwei.zw.rag.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * 文档预览切块请求。
 *
 * <p>刻意用 POJO 而不是 record（对齐 aicube-robot）：{@code @ModelAttribute} 走的是
 * <b>属性绑定</b>，record 走构造器绑定，构造器参数只能从请求的 String 参数里转，
 * {@link MultipartFile} 这种 part 类型拿不到，会绑成 null。
 *
 * <p>参数都给默认值，前端只传 file + 两个类型也能跑。
 */
@Data
public class DocumentPreviewRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "文件不能为空")
    private MultipartFile file;

    /** 解析器：TIKA / MINERU */
    private String parserType;

    /** 切块器：SLIDING / RECURSIVE / HEADING */
    private String splitterType;

    /** 切块大小 */
    private Integer chunkSize = 500;

    /** 重叠长度 */
    private Integer overlap = 50;

    /** 父块最大字符数（仅 HEADING 父子分块有效） */
    private Integer maxParentSize = 5000;

    /** 标题切块级别（仅 HEADING 有效） */
    private Integer headingLevel = 2;

    /** 是否父子分块（仅 HEADING 有效） */
    private Boolean parentChild = false;

    /** 是否开启图片语义理解（仅 MINERU 有效，需配置多模态模型） */
    private Boolean enableImageUnderstanding = false;

    /** 解析器归一化：空则 TIKA。 */
    public String parserOrDefault() {
        return blank(parserType) ? "TIKA" : parserType.trim().toUpperCase(Locale.ROOT);
    }

    /** 切块器归一化：空则 RECURSIVE。 */
    public String splitterOrDefault() {
        return blank(splitterType) ? "RECURSIVE" : splitterType.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
