package com.contenido.domain.search.controller

import com.contenido.domain.search.dto.SearchChannelResponse
import com.contenido.domain.search.dto.SearchContentResponse
import com.contenido.domain.search.service.SearchService
import com.contenido.global.response.ApiResponse
import org.springframework.data.domain.Page
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/search")
class SearchController(
    private val searchService: SearchService,
) {

    /**
     * GET /api/v1/search/channels
     * @param keyword  검색어 (필수)
     * @param category 카테고리 필터 (LOVE/CHASE/PSYCHOLOGICAL/SPORTS/TRAVEL/RACE/MUSIC/COOKING/PARTY)
     * @param sortBy   정렬 기준: relevance(기본) | subscriberCount
     */
    @GetMapping("/channels")
    fun searchChannels(
        @RequestParam keyword: String,
        @RequestParam(required = false) category: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(required = false) sortBy: String?,
    ): ApiResponse<Page<SearchChannelResponse>> {
        return ApiResponse.ok(searchService.searchChannels(keyword, category, page, size, sortBy))
    }

    /**
     * GET /api/v1/search/contents
     * @param keyword     검색어 (필수)
     * @param category    카테고리 필터
     * @param sourceType  콘텐츠 타입 필터: EVENT | POST
     * @param sortBy      정렬 기준: relevance(기본) | viewCount | likeCount
     */
    @GetMapping("/contents")
    fun searchContents(
        @RequestParam keyword: String,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) sourceType: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(required = false) sortBy: String?,
    ): ApiResponse<Page<SearchContentResponse>> {
        return ApiResponse.ok(searchService.searchContents(keyword, category, sourceType, page, size, sortBy))
    }
}
