package io.dockistry.platform.projects.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.github.slugify.Slugify

@Configuration
class SlugifyConfig {
    @Bean
    fun slugifyBuilder(): Slugify {
        return Slugify.builder().build()
    }
}
