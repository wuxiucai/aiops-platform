package com.aiops.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 LocalDateTime 序列化/反序列化：yyyy-MM-dd HH:mm:ss（任务书 §2.3）。
 * application.yml 的 spring.jackson.date-format 只影响 java.util.Date，
 * 对 JSR310 的 LocalDateTime 必须显式注册 ser/deser。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer aiopsJacksonCustomizer() {
        return builder -> builder
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(FMT))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(FMT));
    }
}
