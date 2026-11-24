package com.ziwei.zw.skill;

/**
 * 技能展示信息。
 *
 * @param name        技能名（= 目录名）
 * @param skillPath   绝对路径
 * @param description frontmatter 里的 description
 * @param enabled     是否启用
 */
public record SkillInfo(String name, String skillPath, String description, boolean enabled) {
}
