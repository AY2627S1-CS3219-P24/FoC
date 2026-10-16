package com.cs3219.foc.user;

import com.cs3219.foc.user.config.AuthProperties;
import com.cs3219.foc.user.config.AvatarProperties;
import com.cs3219.foc.user.config.UserProvisioningProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, AvatarProperties.class, UserProvisioningProperties.class})
@EnableScheduling
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
