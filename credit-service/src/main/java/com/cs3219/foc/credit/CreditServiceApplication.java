package com.cs3219.foc.credit;

import com.cs3219.foc.credit.config.CreditProperties;
import com.cs3219.foc.credit.config.EventMessagingProperties;
import com.cs3219.foc.credit.config.EventPublicationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({CreditProperties.class, EventPublicationProperties.class, EventMessagingProperties.class
})
public class CreditServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CreditServiceApplication.class, args);
    }
}
