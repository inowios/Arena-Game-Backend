package com.arenacity.common.response

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    @SerialName("code") val code: Int,
    @SerialName("data") val data: T? = null,
    @SerialName("errors") val errors: List<String>? = null,
    @SerialName("requestId") val requestId: String? = null,
)

fun <T> T.toSuccessResponse(code: Int = HttpStatusCode.OK.value) = ApiResponse(code = code, data = this)
fun String.toErrorResponse(code: Int) = ApiResponse<Nothing>(code = code, errors = listOf(this))
