package com.ziwei.zw.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ziwei.zw.common.BizException;
import com.ziwei.zw.config.ZwProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能管理：文件系统（skills 目录）↔ 数据库双向同步。
 *
 * <p>骨架照抄 aicube-robot 的 SkillManager，取舍：
 * <ul>
 *   <li>同步**只增改不删** —— 多个环境共用一套 skills 目录时，删孤儿会误删别人的记录；</li>
 *   <li>路径一律「skillsDir + name」现拼，不信任 DB 里存的绝对路径；</li>
 *   <li>技能名校验挡路径穿越（{@code ..} / {@code /} / {@code \} / {@code :}）。</li>
 * </ul>
 */
@Slf4j
@Component
public class SkillManager {

    private static final String SKILL_FILE = "SKILL.md";
    /** 压缩包内查找 SKILL.md 的最大目录深度 */
    private static final int MAX_FIND_DEPTH = 3;

    private final Path skillsDir;
    private final SkillMapper skillMapper;

    public SkillManager(ZwProperties props, SkillMapper skillMapper) {
        this.skillsDir = Path.of(props.getAgent().getSkillsDir()).toAbsolutePath().normalize();
        this.skillMapper = skillMapper;
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.warn("[skill] skills 目录创建失败: {}", e.getMessage());
        }
        syncFilesystemToDb();
    }

    @Scheduled(fixedDelay = 180_000, initialDelay = 180_000)
    void scheduledSync() {
        syncFilesystemToDb();
    }

    // ==================== 同步 ====================

    private void syncFilesystemToDb() {
        if (!Files.isDirectory(skillsDir)) {
            log.warn("[skill] skills 目录不存在: {}", skillsDir);
            return;
        }
        List<String> fsNames = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, Files::isDirectory)) {
            for (Path dir : stream) {
                if (Files.isRegularFile(dir.resolve(SKILL_FILE))) {
                    fsNames.add(dir.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("[skill] 扫描 skills 目录失败: {}", e.getMessage());
            return;
        }

        List<AgentxSkill> dbSkills = skillMapper.selectList(null);
        for (String name : fsNames) {
            String desc = readFrontmatterValue(skillsDir.resolve(name).resolve(SKILL_FILE), "description");
            AgentxSkill existing = dbSkills.stream()
                    .filter(s -> name.equals(s.getName())).findFirst().orElse(null);
            if (existing == null) {
                AgentxSkill entity = new AgentxSkill();
                entity.setName(name);
                entity.setSkillPath(name);
                entity.setDescription(desc == null ? "" : desc);
                entity.setEnabled(1);
                skillMapper.insert(entity);
                log.info("[skill] 存量技能入库: {}", name);
            } else if (!safeEquals(desc, existing.getDescription()) || !name.equals(existing.getSkillPath())) {
                skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                        .eq(AgentxSkill::getName, name)
                        .set(AgentxSkill::getSkillPath, name)
                        .set(AgentxSkill::getDescription, desc == null ? "" : desc));
                log.info("[skill] 技能信息刷新: {}", name);
            }
        }
    }

    // ==================== 查询 / 变更 ====================

    public List<SkillInfo> list() {
        return skillMapper.selectList(new LambdaQueryWrapper<AgentxSkill>().orderByAsc(AgentxSkill::getName))
                .stream()
                .map(s -> new SkillInfo(
                        s.getName(),
                        skillsDir.resolve(s.getName()).toAbsolutePath().toString(),
                        s.getDescription() == null ? "" : s.getDescription(),
                        s.getEnabled() != null && s.getEnabled() == 1))
                .toList();
    }

    public boolean isEnabled(String name) {
        AgentxSkill s = skillMapper.selectOne(new LambdaQueryWrapper<AgentxSkill>()
                .eq(AgentxSkill::getName, name));
        return s != null && s.getEnabled() != null && s.getEnabled() == 1;
    }

    /** 所有已启用技能名（通用智能体挂全部技能时用）。 */
    public List<String> enabledSkillNames() {
        return skillMapper.selectList(new LambdaQueryWrapper<AgentxSkill>()
                        .eq(AgentxSkill::getEnabled, 1))
                .stream().map(AgentxSkill::getName).toList();
    }

    /** 绝对路径（skillsDir + name）；目录不存在返回 null。 */
    public String resolveSkillDir(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Path dir = skillsDir.resolve(name.trim());
        return Files.isDirectory(dir) ? dir.toAbsolutePath().toString() : null;
    }

    /** 上传 zip 包安装技能：解压 → 找 SKILL.md → 取 frontmatter 的 name/description → 落目录 + 入库。 */
    public SkillInfo uploadZip(MultipartFile file) throws IOException {
        String original = file.getOriginalFilename();
        if (original == null || !original.toLowerCase().endsWith(".zip")) {
            throw BizException.badRequest("仅支持 .zip 文件");
        }
        Path tempDir = Files.createTempDirectory("zw-skill-");
        try {
            unzip(file.getInputStream(), tempDir);
            Path skillMd = findFile(tempDir, SKILL_FILE, MAX_FIND_DEPTH);
            if (skillMd == null) {
                throw BizException.badRequest("压缩包中未找到 " + SKILL_FILE);
            }
            Path sourceDir = skillMd.getParent();
            String content = readTextAuto(skillMd);
            String name = frontmatterValue(content, "name");
            if (name == null || name.isBlank()) {
                // SKILL.md 没写 name 时回退：
                //  ① zip 里有一层包装目录 → 用目录名（常规情况）
                //  ② 文件直接躺在 zip 根目录 → sourceDir 就是临时目录，用 zip 文件名
                //     （不能拿临时目录名，否则会变成 zw-skill-1234567 这种垃圾名）
                String dirName = sourceDir.getFileName() == null
                        ? "" : sourceDir.getFileName().toString();
                if (sourceDir.equals(tempDir) || dirName.startsWith("zw-skill-")) {
                    name = stripZipExt(original);
                } else {
                    name = dirName;
                }
            }
            name = name.trim();
            validateName(name);

            String description = frontmatterValue(content, "description");
            if (description == null) {
                description = "";
            }

            Path targetDir = skillsDir.resolve(name);
            if (Files.isDirectory(targetDir)) {
                deleteRecursively(targetDir);
            }
            Files.createDirectories(skillsDir);
            copyRecursively(sourceDir, targetDir);

            AgentxSkill existing = skillMapper.selectOne(new LambdaQueryWrapper<AgentxSkill>()
                    .eq(AgentxSkill::getName, name));
            boolean enabled;
            if (existing == null) {
                AgentxSkill entity = new AgentxSkill();
                entity.setName(name);
                entity.setSkillPath(name);
                entity.setDescription(description.trim());
                entity.setEnabled(1);
                entity.setFileName(original);
                skillMapper.insert(entity);
                enabled = true;
                log.info("[skill] 新技能上传: {}（enabled=1）", name);
            } else {
                skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                        .eq(AgentxSkill::getName, name)
                        .set(AgentxSkill::getSkillPath, name)
                        .set(AgentxSkill::getDescription, description.trim())
                        .set(AgentxSkill::getFileName, original));
                enabled = existing.getEnabled() != null && existing.getEnabled() == 1;
                log.info("[skill] 技能覆盖上传: {}（enabled={}）", name, enabled);
            }
            return new SkillInfo(name, targetDir.toAbsolutePath().toString(), description.trim(), enabled);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public boolean toggleEnabled(String name, boolean enabled) {
        AgentxSkill existing = skillMapper.selectOne(new LambdaQueryWrapper<AgentxSkill>()
                .eq(AgentxSkill::getName, name));
        if (existing == null) {
            throw BizException.notFound("技能不存在：" + name);
        }
        skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                .eq(AgentxSkill::getName, name)
                .set(AgentxSkill::getEnabled, enabled ? 1 : 0));
        log.info("[skill] 切换: {} → enabled={}", name, enabled);
        return enabled;
    }

    public boolean delete(String name) {
        validateName(name);
        Path dir = skillsDir.resolve(name);
        boolean dirDeleted = false;
        if (Files.isDirectory(dir)) {
            dirDeleted = deleteRecursively(dir);
        }
        int rows = skillMapper.delete(new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, name));
        log.info("[skill] 删除: {}（dir={}, db={}）", name, dirDeleted, rows > 0);
        return dirDeleted || rows > 0;
    }

    // ==================== 私有工具 ====================

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw BizException.badRequest("技能名称不能为空");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(":")) {
            throw BizException.badRequest("非法技能名称：" + name);
        }
        if (!skillsDir.resolve(name).normalize().startsWith(skillsDir)) {
            throw BizException.badRequest("非法技能名称：" + name);
        }
    }

    private void unzip(InputStream in, Path targetDir) throws IOException {
        // 编码自适应：Windows 资源管理器「压缩为 zip」写的是 GBK 文件名且不打 UTF-8 标志位，
        // 直接按 UTF-8 解会抛 "malformed input off : N, length : 1"。
        // 先把整个包读进内存，按实际内容探测编码再解压。
        byte[] all = in.readAllBytes();
        Charset cs = detectZipCharset(all);
        log.info("[skill] zip 文件名编码探测结果: {}", cs);
        unzipWith(all, targetDir, cs);
    }

    /** 按 UTF-8 解压；遇到非法字节再退回 GBK（中文 Windows 的默认 zip 编码）。 */
    private void unzipWith(byte[] all, Path targetDir, Charset cs) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(all), cs)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw BizException.badRequest("压缩包包含非法路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /** 去掉 .zip 后缀，用作技能名兜底 */
    private static String stripZipExt(String fileName) {
        if (fileName == null) {
            return "";
        }
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(0, i) : fileName;
    }

    /**
     * 读文本文件，UTF-8 优先，非法字节时退回 GBK。
     * Windows 上手工写的 SKILL.md 常是 GBK，直接按 UTF-8 读会抛 MalformedInputException。
     */
    private static String readTextAuto(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /**
     * 探测 zip 内文件名的编码：逐个 entry 用 UTF-8 严格解码，
     * 一旦失败就判定为 GBK（Windows 压缩工具的默认行为）。
     */
    private Charset detectZipCharset(byte[] all) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(all),
                StandardCharsets.UTF_8)) {
            while (zis.getNextEntry() != null) {
                zis.closeEntry();
            }
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            // MalformedInputException / IllegalArgumentException 等都说明不是 UTF-8
            return Charset.forName("GBK");
        }
    }

    private Path findFile(Path root, String fileName, int maxDepth) throws IOException {
        try (var stream = Files.walk(root, maxDepth)) {
            return stream.filter(p -> fileName.equals(p.getFileName().toString()))
                    .filter(p -> p.getParent() != null)
                    .min(Comparator.comparingInt(p -> p.getNameCount()))
                    .orElse(null);
        }
    }

    private void copyRecursively(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (Path p : stream.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 删除目录，失败不抛（临时目录清理用）。 */
    private boolean deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return false;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 忽略单个文件删除失败
                }
            });
            return true;
        } catch (IOException e) {
            log.warn("[skill] 删除目录失败 {}: {}", dir, e.getMessage());
            return false;
        }
    }

    private String readFrontmatterValue(Path file, String key) {
        try {
            return frontmatterValue(Files.readString(file, StandardCharsets.UTF_8), key);
        } catch (IOException e) {
            return null;
        }
    }

    /** 极简 frontmatter 解析：只认文件开头 `---` 包裹的 `key: value`，够用就行。 */
    private String frontmatterValue(String content, String key) {
        if (content == null) {
            return null;
        }
        // 去掉 UTF-8 BOM：Windows / 部分编辑器保存的 SKILL.md 会带 \uFEFF，
        // 导致第一行变成 "\uFEFF---"，frontmatter 判不出来，name/description 全空
        content = content.replace("\uFEFF", "");
        String[] lines = content.replace("\r\n", "\n").split("\n");
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            return null;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if ("---".equals(line)) {
                break;
            }
            int idx = line.indexOf(':');
            if (idx > 0 && key.equals(line.substring(0, idx).trim())) {
                return line.substring(idx + 1).trim().replaceAll("^[\"']|[\"']$", "");
            }
        }
        return null;
    }

    private boolean safeEquals(String a, String b) {
        return a == null ? b == null || b.isEmpty() : a.equals(b);
    }
}
