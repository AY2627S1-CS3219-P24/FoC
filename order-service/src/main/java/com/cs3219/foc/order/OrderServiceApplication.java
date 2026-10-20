package com.cs3219.foc.order;

import com.cs3219.foc.order.config.EventMessagingProperties;
import com.cs3219.foc.order.config.EventPublicationProperties;
import com.cs3219.foc.order.config.OrderProperties;
import com.cs3219.foc.order.config.ServiceClientProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({
    OrderProperties.class,
    ServiceClientProperties.class,
    EventPublicationProperties.class,
    EventMessagingProperties.class
})
public class OrderServiceApplication {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
