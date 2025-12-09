package com.example.epubreader.ui

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import javax.swing.text.Element
import javax.swing.text.StyleConstants
import javax.swing.text.View
import javax.swing.text.ViewFactory
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.ImageView

/**
 * HTMLEditorKit that understands <img src="data:..."> URIs, which the default Swing kit cannot render.
 */
class DataUriHtmlEditorKit : HTMLEditorKit() {
    private val viewFactory = DataUriHtmlFactory()

    override fun getViewFactory(): ViewFactory = viewFactory

    private class DataUriHtmlFactory : HTMLFactory() {
        override fun create(elem: Element): View {
            val name = elem.getAttributes().getAttribute(StyleConstants.NameAttribute)
            if (name === HTML.Tag.IMG) {
                return DataUriImageView(elem)
            }
            return super.create(elem)
        }
    }

    private class DataUriImageView(element: Element) : ImageView(element) {
        override fun getImageURL(): URL? {
            val attributes = element.attributes
            val src = attributes.getAttribute(HTML.Attribute.SRC) as? String ?: return super.getImageURL()
            if (!src.startsWith("data:", ignoreCase = true)) {
                return super.getImageURL()
            }
            val parsed = parseDataUri(src) ?: return null
            val uniqueSpec = "memory:data:" + System.identityHashCode(this)
            val handler = DataUriStreamHandler(parsed.mimeType, parsed.data)
            return try {
                URL(null, uniqueSpec, handler)
            } catch (e: MalformedURLException) {
                null
            }
        }
    }

    private class DataUriStreamHandler(
        private val contentType: String,
        private val data: ByteArray
    ) : URLStreamHandler() {
        override fun openConnection(u: URL): URLConnection {
            return object : URLConnection(u) {
                override fun connect() {}

                override fun getInputStream(): InputStream {
                    return ByteArrayInputStream(data)
                }

                override fun getContentType(): String {
                    // Explicitly reference the outer handler to avoid recursive calls through the getter.
                    return this@DataUriStreamHandler.contentType
                }
            }
        }
    }

    private data class ParsedDataUri(
        val mimeType: String,
        val data: ByteArray
    )

    companion object {
        private fun parseDataUri(uri: String): ParsedDataUri? {
            if (!uri.startsWith("data:", ignoreCase = true)) return null
            val commaIndex = uri.indexOf(',')
            if (commaIndex <= 4 || commaIndex >= uri.length - 1) return null
            val header = uri.substring(5, commaIndex)
            val payload = uri.substring(commaIndex + 1)
            val segments = header.split(';')
            val mimeType = segments.firstOrNull()?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val isBase64 = segments.any { it.equals("base64", ignoreCase = true) }
            val data = if (isBase64) {
                try {
                    Base64.getDecoder().decode(payload)
                } catch (iae: IllegalArgumentException) {
                    return null
                }
            } else {
                payload.toByteArray(StandardCharsets.UTF_8)
            }
            return ParsedDataUri(mimeType.lowercase(Locale.ROOT), data)
        }
    }
}
