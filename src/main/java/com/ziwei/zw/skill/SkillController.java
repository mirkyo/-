package com.ziwei.zw.skill;

import com.ziwei.zw.common.BizException;
import com.ziwei.zw.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 技能管理：列表 / zip 上传 / 启停 / 删除。
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillManager skillManager;

    @GetMapping
    public R<List<SkillInfo>> list() {
        return R.ok(skillManager.list());
    }

    @PostMapping("/upload")
    public R<SkillInfo> upload(@RequestPart("file") MultipartFile file) {
        try {
            return R.ok(skillManager.uploadZip(file));
        } catch (IOException e) {
            throw BizException.badRequest("技能包解析失败：" + e.getMessage());
        }
    }

    @PutMapping("/{name}/enabled")
    public R<Boolean> toggle(@PathVariable String name, @RequestParam boolean enabled) {
        return R.ok(skillManager.toggleEnabled(name, enabled));
    }

    @DeleteMapping("/{name}")
    public R<Void> delete(@PathVariable String name) {
        skillManager.delete(name);
        return R.ok();
    }
}
