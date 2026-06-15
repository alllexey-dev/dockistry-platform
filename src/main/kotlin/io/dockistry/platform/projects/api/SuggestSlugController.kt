package io.dockistry.platform.projects.api

import com.github.slugify.Slugify
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/util/suggest_slug")
class SuggestSlugController(private val slugify: Slugify) {
    @GetMapping("")
    fun suggestSlug(@RequestParam toSlug: String): ApiResponse<String> {
        return ApiResponse(slugify.slugify(toSlug))
    }
}
