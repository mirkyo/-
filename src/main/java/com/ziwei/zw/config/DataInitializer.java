package com.ziwei.zw.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ziwei.zw.user.User;
import com.ziwei.zw.user.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动初始化：管理员账号（已去掉多租户）。
 *
 * <p>幂等：已存在就跳过。密码不硬编码 bcrypt 值，由 {@link PasswordEncoder} 现算。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final ZwProperties props;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        ZwProperties.Init init = props.getInit();

        User admin = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, init.getUsername()));
        if (admin == null) {
            admin = new User();
            admin.setTenantId(1L);
            admin.setUsername(init.getUsername());
            admin.setPassword(passwordEncoder.encode(init.getPassword()));
            admin.setNickname("超级管理员");
            admin.setEmail("");
            admin.setAvatar("");
            admin.setStatus(0);
            admin.setRole("SUPER_ADMIN");
            admin.setFailCount(0);
            userMapper.insert(admin);
            log.info("[init] 创建超管 username={}（初始密码见 app.init.password，请尽快修改）", admin.getUsername());
        }
    }
}
