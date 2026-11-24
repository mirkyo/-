package com.ziwei.zw.session;

import com.ziwei.zw.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 历史会话：列表 / 明细 / 删除。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(sessionService.list(page, size));
    }

    @GetMapping("/{conversationId}")
    public R<Map<String, Object>> detail(@PathVariable String conversationId) {
        return R.ok(Map.of(
                "conversationId", conversationId,
                "messages", sessionService.messages(conversationId)));
    }

    @DeleteMapping("/{conversationId}")
    public R<Void> delete(@PathVariable String conversationId) {
        sessionService.delete(conversationId);
        return R.ok();
    }
}
