package com.missingtable.scorer.data.api

import java.lang.reflect.Type
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Converter
import retrofit2.Retrofit

/**
 * FastAPI returns 200 with a literal `null` body when a GET finds nothing
 * (lineup for a team that has none yet). kotlinx.serialization can't decode
 * `null` into a non-null type, so without this every such call throws and
 * looks like a network failure (SB-1511). Reads the body once: a blank or
 * `null` body becomes null, anything else goes to the next converter.
 * Register it before the JSON converter.
 */
object NullBodyConverterFactory : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *> {
        val next = retrofit.nextResponseBodyConverter<Any?>(this, type, annotations)
        return Converter<ResponseBody, Any?> { body ->
            val contentType = body.contentType()
            val text = body.string()
            if (text.isBlank() || text.trim() == "null") null
            else next.convert(text.toResponseBody(contentType))
        }
    }
}
