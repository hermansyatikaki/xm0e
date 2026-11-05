package miku.moe.app

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import com.github.penfeizhou.animation.avif.AVIFDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import java.nio.ByteBuffer
import kotlinx.coroutines.runInterruptible
import okio.BufferedSource

/**
 * Decoder Coil untuk AVIF animasi (brand "avis"), yang tidak didukung ImageDecoder bawaan Android
 * maupun coil-gif. Di Android < 12 (API 31) decoder ini juga dipakai untuk AVIF statis,
 * karena sistem belum bisa membuka AVIF sama sekali.
 */
class AnimatedAvifDecoder(
    private val source: ImageSource,
    private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult = runInterruptible {
        val bytes = source.source().readByteArray()
        if (Build.VERSION.SDK_INT >= 31 && isSingleFrame(bytes)) {
            val still = decodeStill(bytes)
            if (still != null) {
                return@runInterruptible DecodeResult(
                    drawable = BitmapDrawable(options.context.resources, still),
                    isSampled = false
                )
            }
        }
        val loader = object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        }
        DecodeResult(drawable = AVIFDrawable(loader), isSampled = false)
    }

    @RequiresApi(31)
    private fun decodeStill(bytes: ByteArray): Bitmap? {
        return try {
            val imageSource = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(imageSource) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun isSingleFrame(bytes: ByteArray): Boolean {
        val limit = bytes.size - 16
        var i = 0
        while (i <= limit) {
            if (bytes[i] == 's'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
                bytes[i + 2] == 's'.code.toByte() && bytes[i + 3] == 'z'.code.toByte()
            ) {
                val count = ((bytes[i + 12].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 13].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 14].toInt() and 0xFF) shl 8) or
                    (bytes[i + 15].toInt() and 0xFF)
                return count in 0..1
            }
            i++
        }
        return false
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val head = readHead(result.source.source()) ?: return null
            if (head.length < 12 || head.substring(4, 8) != "ftyp") return null
            val animated = head.contains("avis")
            val avif = animated || head.contains("avif")
            if (!avif) return null
            if (!animated && Build.VERSION.SDK_INT >= 31) return null
            return AnimatedAvifDecoder(result.source, options)
        }

        private fun readHead(source: BufferedSource): String? {
            return try {
                val peek = source.peek()
                if (!peek.request(16)) return null
                val size = if (peek.request(64)) 64L else peek.buffer.size
                String(peek.readByteArray(size), Charsets.ISO_8859_1)
            } catch (e: Exception) {
                null
            }
        }
    }
}
