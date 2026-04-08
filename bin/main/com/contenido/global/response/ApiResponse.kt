package com.contenido.global.response

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null,
) {
    companion object {
        fun <T> ok(data: T, message: String = "OK"): ApiResponse<T> =
            ApiResponse(success = true, message = message, data = data)

        fun <T> created(data: T, message: String = "Created"): ApiResponse<T> =
            ApiResponse(success = true, message = message, data = data)

        fun fail(message: String): ApiResponse<Nothing> =
            ApiResponse(success = false, message = message)
    }
}
