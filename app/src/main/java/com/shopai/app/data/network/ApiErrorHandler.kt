package com.shopai.app.data.network

import android.content.Context
import com.shopai.app.R
import com.shopai.app.data.AppContainer
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ApiErrorHandler(private val context: Context) {

    data class ResolvedError(
        val message: String,
        val showAsAlert: Boolean,
    )

    private enum class ErrorKind {
        NO_INTERNET,
        SERVER_DOWN,
        TIMEOUT,
        SERVER_ERROR,
        HTTP_CLIENT,
        UNKNOWN,
    }

    fun resolve(throwable: Throwable, fallback: String): ResolvedError {
        val kind = classify(throwable)
        val message = when (kind) {
            ErrorKind.NO_INTERNET -> context.getString(R.string.error_no_internet)
            ErrorKind.SERVER_DOWN -> context.getString(R.string.error_server_down)
            ErrorKind.TIMEOUT -> context.getString(R.string.error_timeout)
            ErrorKind.SERVER_ERROR -> context.getString(R.string.error_server_error)
            ErrorKind.HTTP_CLIENT -> parseHttpMessage(throwable) ?: fallback
            ErrorKind.UNKNOWN -> fallback
        }
        val showAsAlert = when (kind) {
            ErrorKind.NO_INTERNET,
            ErrorKind.SERVER_DOWN,
            ErrorKind.TIMEOUT,
            ErrorKind.SERVER_ERROR,
            -> true
            ErrorKind.HTTP_CLIENT,
            ErrorKind.UNKNOWN,
            -> false
        }
        return ResolvedError(message = message, showAsAlert = showAsAlert)
    }

    private fun classify(throwable: Throwable): ErrorKind {
        return when (throwable) {
            is SocketTimeoutException -> ErrorKind.TIMEOUT
            is UnknownHostException -> ErrorKind.NO_INTERNET
            is ConnectException -> ErrorKind.SERVER_DOWN
            is IOException -> ErrorKind.NO_INTERNET
            is HttpException -> when (throwable.code()) {
                502, 503, 504 -> ErrorKind.SERVER_DOWN
                in 500..599 -> ErrorKind.SERVER_ERROR
                else -> ErrorKind.HTTP_CLIENT
            }
            else -> ErrorKind.UNKNOWN
        }
    }

    private fun parseHttpMessage(throwable: Throwable): String? {
        if (throwable !is HttpException) return null
        val body = throwable.response()?.errorBody()?.string()
        if (body != null && body.contains("\"message\"")) {
            val match = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}

fun AppContainer.presentApiError(
    throwable: Throwable,
    fallback: String,
    onInline: (String) -> Unit,
    onAlert: (String) -> Unit,
) {
    val resolved = apiErrorHandler.resolve(throwable, fallback)
    if (resolved.showAsAlert) {
        onAlert(resolved.message)
    } else {
        onInline(resolved.message)
    }
}
