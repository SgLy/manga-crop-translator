package me.sgly.translator.runtime

import android.content.Context
import me.sgly.translator.R
import me.sgly.translator.api.MissingApiKeyException
import me.sgly.translator.api.ModelRequestFailedException
import me.sgly.translator.api.ModelResponseEmptyException
import me.sgly.translator.api.ModelResponseParseException
import me.sgly.translator.api.ModelRole
import me.sgly.translator.logs.AppLog

internal fun Exception.userMessage(context: Context): String =
    when (this) {
        is MissingApiKeyException -> {
            context.getString(
                when (role) {
                    ModelRole.Visual -> R.string.error_missing_visual_model_api_key
                    ModelRole.Translate -> R.string.error_missing_translate_model_api_key
                },
            )
        }

        is ModelRequestFailedException -> {
            context.getString(R.string.error_request_failed, statusCode)
        }

        is ModelResponseParseException -> {
            context.getString(R.string.error_response_unparsable)
        }

        is ModelResponseEmptyException -> {
            context.getString(R.string.error_response_empty)
        }

        else -> {
            AppLog.e("TranslatorState", "Unhandled error: ${message ?: javaClass.simpleName}", this)
            context.getString(R.string.error_unknown)
        }
    }
