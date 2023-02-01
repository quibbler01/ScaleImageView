package cn.quibbler.scaleimageview.decoder;

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import cn.quibbler.scaleimageview.SubsamplingScaleImageView
import java.io.InputStream
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Default implementation of {@link com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder}
 * using Android's {@link android.graphics.BitmapRegionDecoder}, based on the Skia library. This
 * works well in most circumstances and has reasonable performance due to the cached decoder instance,
 * however it has some problems with grayscale, indexed and CMYK images.
 *
 * A {@link ReadWriteLock} is used to delegate responsibility for multi threading behaviour to the
 * {@link BitmapRegionDecoder} instance on SDK &gt;= 21, whilst allowing this class to block until no
 * tiles are being loaded before recycling the decoder. In practice, {@link BitmapRegionDecoder} is
 * synchronized internally so this has no real impact on performance.
 */
public class SkiaImageRegionDecoder : ImageRegionDecoder {

    companion object {
        private const val FILE_PREFIX = "file://"
        private const val ASSET_PREFIX = "$FILE_PREFIX/android_asset/"
        private const val RESOURCE_PREFIX = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://"
    }

    private val decoderLock = ReentrantReadWriteLock(true)

    private var decoder: BitmapRegionDecoder? = null

    private val bitmapConfig: Bitmap.Config

    constructor(bitmapConfig: Bitmap.Config? = null) {
        val globalBitmapConfig: Bitmap.Config? = SubsamplingScaleImageView.preferredBitmapConfig
        this.bitmapConfig = bitmapConfig ?: (globalBitmapConfig ?: Bitmap.Config.RGB_565)

    }

    @Throws(Exception::class)
    override fun init(context: Context, uri: Uri): Point {
        val uriString = uri.toString()
        if (uriString.startsWith(RESOURCE_PREFIX)) {
            val packageName = uri.authority
            val res = if (context.packageName == packageName) {
                context.resources
            } else {
                context.packageManager.getResourcesForApplication(packageName!!)
            }
            var id: Int = 0
            val segments = uri.pathSegments
            val size = segments.size
            if (size == 2 && segments[0] == "drawable") {
                val resName = segments[1]
                id = res.getIdentifier(resName, "drawable", packageName)
            } else if (size == 1 && TextUtils.isDigitsOnly(segments[0])) {
                try {
                    id = segments[0].toInt()
                } catch (ignored: NumberFormatException) {
                }
            }
            decoder = BitmapRegionDecoder.newInstance(context.resources.openRawResource(id), false)
        } else if (uriString.startsWith(ASSET_PREFIX)) {
            val assetName = uriString.substring(ASSET_PREFIX.length)
            decoder = BitmapRegionDecoder.newInstance(context.assets.open(assetName, AssetManager.ACCESS_RANDOM), false)
        } else if (uriString.startsWith(FILE_PREFIX)) {
            decoder = BitmapRegionDecoder.newInstance(uriString.substring(FILE_PREFIX.length), false)
        } else {
            var inputStream: InputStream? = null
            try {
                inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Content resolver returned null stream. Unable to initialise with uri.")
                decoder = BitmapRegionDecoder.newInstance(inputStream, false)
            } finally {
                try {
                    inputStream?.close()
                } catch (ignored: Exception) {
                }
            }
        }
        return Point(decoder?.width ?: 0, decoder?.height ?: 0)
    }

    @Throws(IllegalStateException::class)
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        getDecodeLock().lock()
        try {
            return if (decoder != null && !decoder!!.isRecycled) {
                val options = BitmapFactory.Options()
                options.inSampleSize = sampleSize
                options.inPreferredConfig = bitmapConfig
                val bitmap = decoder!!.decodeRegion(sRect, options) ?: throw RuntimeException("Skia image decoder returned null bitmap - image format may not be supported")
                bitmap
            } else {
                throw IllegalStateException("Cannot decode region after decoder has been recycled")
            }
        } finally {
            getDecodeLock().unlock()
        }
    }

    override fun isReady(): Boolean {
        return decoder != null && !decoder!!.isRecycled
    }

    override fun recycle() {
        decoderLock.writeLock().lock()
        try {
            decoder?.recycle()
            decoder = null
        } finally {
            decoderLock.writeLock().unlock()
        }
    }

    /**
     * Before SDK 21, BitmapRegionDecoder was not synchronized internally. Any attempt to decode
     * regions from multiple threads with one decoder instance causes a segfault. For old versions
     * use the write lock to enforce single threaded decoding.
     */
    private fun getDecodeLock(): Lock {
        return if (Build.VERSION.SDK_INT < 21) {
            decoderLock.writeLock()
        } else {
            decoderLock.readLock()
        }
    }

}
