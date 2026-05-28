package org.example.xqy1._026_silver_residence.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "org.example")
public class AIConfiguration {


    @Bean
    public ChatClient chatClient(ChatModel model) {
        return ChatClient.builder(model)
                .build();
    }

}
