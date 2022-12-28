package cn.quibbler.scaleimageview.decoder

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.TextUtils
import cn.quibbler.scaleimageview.SubsamplingScaleImageView
import java.io.InputStream

class SkiaImageDecoder(bitmapConfig: Bitmap.Config? = null) : ImageDecoder {

    companion object {
        private const val FILE_PREFIX = "file://"
        private const val ASSET_PREFIX = "$FILE_PREFIX/android_asset/"
        private const val RESOURCE_PREFIX = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://"
    }

    private val bitmapConfig: Bitmap.Config

    init {
        val globalBitmapConfig: Bitmap.Config? = SubsamplingScaleImageView.getPreferredBitmapConfig()
        if (bitmapConfig != null) {
            this.bitmapConfig = bitmapConfig
        } else if (globalBitmapConfig != null) {
            this.bitmapConfig = globalBitmapConfig
        } else {
            this.bitmapConfig = Bitmap.Config.RGB_565
        }
    }

    override fun decode(context: Context, uri: Uri): Bitmap {
        val uriString = uri.toString()
        val options = BitmapFactory.Options().apply { inPreferredConfig = bitmapConfig }
        var bitmap: Bitmap? = null
        if (uriString.startsWith(RESOURCE_PREFIX)) {
            val packageName = uri.authority
            val res: Resources = if (context.packageName == packageName) {
                context.resources
            } else {
                context.packageManager.getResourcesForApplication(packageName!!)
            }
            var id: Int = 0
            val segments: List<String> = uri.pathSegments
            val size: Int = segments.size
            if (size == 2 && segments[0] == "drawable") {
                val resName = segments[1]
                id = res.getIdentifier(resName, "drawable", packageName)
            } else if (size == 1 && TextUtils.isDigitsOnly(segments[0])) {
                try {
                    id = segments[0].toInt()
                } catch (ignored: NumberFormatException) {
                }
            }
            bitmap = BitmapFactory.decodeResource(context.resources, id, options)
        } else if (uriString.startsWith(ASSET_PREFIX)) {
            val assetName = uriString.substring(ASSET_PREFIX.length)
            bitmap = BitmapFactory.decodeStream(context.assets.open(assetName), null, options)
        } else if (uriString.startsWith(FILE_PREFIX)) {
            bitmap = BitmapFactory.decodeFile(uriString.substring(FILE_PREFIX.length), options)
        } else {
            var inputStream: InputStream? = null
            try {
                val contentResolver = context.contentResolver
                inputStream = contentResolver.openInputStream(uri)
                bitmap = BitmapFactory.decodeStream(inputStream, null, options)
            } finally {
                try {
                    inputStream?.close()
                } catch (ignore: Exception) {
                }
            }
        }
        if (bitmap == null) throw RuntimeException("Skia image region decoder returned null bitmap - image format may not be supported")
        return bitmap
    }

}