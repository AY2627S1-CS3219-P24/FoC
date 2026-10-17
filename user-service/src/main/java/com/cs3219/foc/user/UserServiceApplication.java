package com.cs3219.foc.user;

import com.cs3219.foc.user.config.AccountMailProperties;
import com.cs3219.foc.user.config.AuthProperties;
import com.cs3219.foc.user.config.AvatarProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, AvatarProperties.class, AccountMailProperties.class})
@EnableScheduling
@EnableAsync
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
