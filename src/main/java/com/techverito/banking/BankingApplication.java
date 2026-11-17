package com.techverito.banking;

import com.techverito.banking.config.DocumentProperties;
import com.techverito.banking.config.EventBusProperties;
import com.techverito.banking.config.KafkaEventProperties;
import com.techverito.banking.config.S3Properties;
import com.techverito.banking.config.TaskQueueProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({DocumentProperties.class, S3Properties.class, EventBusProperties.class,
        KafkaEventProperties.class, TaskQueueProperties.class})
public class BankingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankingApplication.class, args);
    }
}
