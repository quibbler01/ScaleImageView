package cn.quibbler.scaleimageview

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.os.Message
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AnyThread
import cn.quibbler.scaleimageview.decoder.*
import cn.quibbler.scaleimageview.decoder.ImageDecoder
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * <p>
 * Displays an image subsampled as necessary to avoid loading too much image data into memory. After zooming in,
 * a set of image tiles subsampled at higher resolution are loaded and displayed over the base layer. During pan and
 * zoom, tiles off screen or higher/lower resolution than required are discarded from memory.
 * </p><p>
 * Tiles are no larger than the max supported bitmap size, so with large images tiling may be used even when zoomed out.
 * </p><p>
 * v prefixes - coordinates, translations and distances measured in screen (view) pixels
 * <br>
 * s prefixes - coordinates, translations and distances measured in rotated and cropped source image pixels (scaled)
 * <br>
 * f prefixes - coordinates, translations and distances measured in original unrotated, uncropped source file pixels
 * </p><p>
 * <a href="https://github.com/davemorrissey/subsampling-scale-image-view">View project on GitHub</a>
 * </p>
 */
class SubsamplingScaleImageView : View {

    companion object {

        private val TAG = SubsamplingScaleImageView::class.java.simpleName

        /** Attempt to use EXIF information on the image to rotate it. Works for external files only.  */
        const val ORIENTATION_USE_EXIF = -1

        /** Display the image file in its native orientation.  */
        const val ORIENTATION_0 = 0

        /** Rotate the image 90 degrees clockwise.  */
        const val ORIENTATION_90 = 90

        /** Rotate the image 180 degrees.  */
        const val ORIENTATION_180 = 180

        /** Rotate the image 270 degrees clockwise.  */
        const val ORIENTATION_270 = 270

        private val VALID_ORIENTATIONS = listOf(
            ORIENTATION_0,
            ORIENTATION_90,
            ORIENTATION_180,
            ORIENTATION_270,
            ORIENTATION_USE_EXIF
        )

        /** During zoom animation, keep the point of the image that was tapped in the same place, and scale the image around it.  */
        const val ZOOM_FOCUS_FIXED = 1

        /** During zoom animation, move the point of the image that was tapped to the center of the screen.  */
        const val ZOOM_FOCUS_CENTER = 2

        /** Zoom in to and center the tapped point immediately without animating.  */
        const val ZOOM_FOCUS_CENTER_IMMEDIATE = 3

        private val VALID_ZOOM_STYLES =
            Arrays.asList(ZOOM_FOCUS_FIXED, ZOOM_FOCUS_CENTER, ZOOM_FOCUS_CENTER_IMMEDIATE)

        /** Quadratic ease out. Not recommended for scale animation, but good for panning.  */
        const val EASE_OUT_QUAD = 1

        /** Quadratic ease in and out.  */
        const val EASE_IN_OUT_QUAD = 2

        private val VALID_EASING_STYLES = listOf(EASE_IN_OUT_QUAD, EASE_OUT_QUAD)

        /** Don't allow the image to be panned off screen. As much of the image as possible is always displayed, centered in the view when it is smaller. This is the best option for galleries.  */
        const val PAN_LIMIT_INSIDE = 1

        /** Allows the image to be panned until it is just off screen, but no further. The edge of the image will stop when it is flush with the screen edge.  */
        const val PAN_LIMIT_OUTSIDE = 2

        /** Allows the image to be panned until a corner reaches the center of the screen but no further. Useful when you want to pan any spot on the image to the exact center of the screen.  */
        const val PAN_LIMIT_CENTER = 3

        private val VALID_PAN_LIMITS = listOf(PAN_LIMIT_INSIDE, PAN_LIMIT_OUTSIDE, PAN_LIMIT_CENTER)

        /** Scale the image so that both dimensions of the image will be equal to or less than the corresponding dimension of the view. The image is then centered in the view. This is the default behaviour and best for galleries.  */
        const val SCALE_TYPE_CENTER_INSIDE = 1

        /** Scale the image uniformly so that both dimensions of the image will be equal to or larger than the corresponding dimension of the view. The image is then centered in the view.  */
        const val SCALE_TYPE_CENTER_CROP = 2

        /** Scale the image so that both dimensions of the image will be equal to or less than the maxScale and equal to or larger than minScale. The image is then centered in the view.  */
        const val SCALE_TYPE_CUSTOM = 3

        /** Scale the image so that both dimensions of the image will be equal to or larger than the corresponding dimension of the view. The top left is shown.  */
        const val SCALE_TYPE_START = 4

        private val VALID_SCALE_TYPES = listOf(
            SCALE_TYPE_CENTER_CROP,
            SCALE_TYPE_CENTER_INSIDE,
            SCALE_TYPE_CUSTOM,
            SCALE_TYPE_START
        )

        /** State change originated from animation.  */
        const val ORIGIN_ANIM = 1

        /** State change originated from touch gesture.  */
        const val ORIGIN_TOUCH = 2

        /** State change originated from a fling momentum anim.  */
        const val ORIGIN_FLING = 3

        /** State change originated from a double tap zoom anim.  */
        const val ORIGIN_DOUBLE_TAP_ZOOM = 4

        // overrides for the dimensions of the generated tiles
        const val TILE_SIZE_AUTO = Int.MAX_VALUE

        private const val MESSAGE_LONG_CLICK = 1

        // A global preference for bitmap format, available to decoder classes that respect it
        var preferredBitmapConfig: Bitmap.Config? = null
            private set
    }

    // Bitmap (preview or full image)
    private var bitmap: Bitmap? = null

    // Whether the bitmap is a preview image
    private var bitmapIsPreview = false

    // Specifies if a cache handler is also referencing the bitmap. Do not recycle if so.
    private var bitmapIsCached = false

    // Uri of full size image
    private var uri: Uri? = null

    // Sample size used to display the whole image when fully zoomed out
    private var fullImageSampleSize: Int = 0

    private var tileMap: MutableMap<Int, MutableList<Tile>>? = null

    // Overlay tile boundaries and other info
    private var debug = false

    // Image orientation setting
    private var orientation = ORIENTATION_0

    // Max scale allowed (prevent infinite zoom)
    private var maxScale: Float = 2f

    // Min scale allowed (prevent infinite zoom)
    private var minScale: Float = minScale()

    // Density to reach before loading higher resolution tiles
    private var minimumTileDpi = -1

    // Pan limiting style
    private var panLimit = PAN_LIMIT_INSIDE

    // Minimum scale type
    private var minimumScaleType = SCALE_TYPE_CENTER_INSIDE

    private var maxTileWidth = TILE_SIZE_AUTO
    private var maxTileHeight = TILE_SIZE_AUTO

    // An executor service for loading of images
    private val executor = AsyncTask.THREAD_POOL_EXECUTOR

    // Whether tiles should be loaded while gestures and animations are still in progress
    private var eagerLoadingEnabled = true

    // Gesture detection settings
    private var panEnabled = true
    var zoomEnabled = true
    private var quickScaleEnabled = true

    // Double tap zoom behaviour
    private var doubleTapZoomScale: Float = 1f
    private var doubleTapZoomStyle = ZOOM_FOCUS_FIXED
    private var doubleTapZoomDuration = 500

    // Current scale and scale at start of zoom
    private var scale: Float = 0f
    private var scaleStart: Float = 0f

    // Screen coordinate of top-left corner of source image
    private var vTranslate: PointF? = null
    private var vTranslateStart: PointF? = null
    private var vTranslateBefore: PointF? = null

    // Source coordinate to center on, used when new position is set externally before view is ready
    private var pendingScale: Float? = 0f
    private var sPendingCenter: PointF? = null
    private var sRequestedCenter: PointF? = null

    // Source image dimensions and orientation - dimensions relate to the unrotated image
    private var sWidth = 0
    private var sHeight = 0
    private var sOrientation = 0
    private var sRegion: Rect? = null
    private var pRegion: Rect? = null

    // Is two-finger zooming in progress
    private var isZooming = false

    // Is one-finger panning in progress
    private var isPanning = false

    // Is quick-scale gesture in progress
    private var isQuickScaling = false

    // Max touches used in current gesture
    private var maxTouchCount = 0

    // Fling detector
    private var detector: GestureDetector? = null
    private var singleDetector: GestureDetector? = null

    // Tile and image decoding
    private var decoder: ImageRegionDecoder? = null
    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)
    private val bitmapDecoderFactory: DecoderFactory<out ImageDecoder> =
        CompatDecoderFactory<ImageDecoder>(SkiaImageDecoder::class.java)
    private var regionDecoderFactory: DecoderFactory<out ImageRegionDecoder> =
        CompatDecoderFactory<ImageRegionDecoder>(SkiaImageRegionDecoder::class.java)


    // Debug values
    private var vCenterStart: PointF? = null
    private var vDistStart: Float = 0f

    // Current quickscale state
    private val quickScaleThreshold: Float
    private var quickScaleLastDistance: Float = 0f
    private var quickScaleMoved = false
    private var quickScaleVLastPoint: PointF? = null
    private var quickScaleSCenter: PointF? = null
    private var quickScaleVStart: PointF? = null

    // Scale and center animation tracking
    private var anim: Anim? = null

    // Whether a ready notification has been sent to subclasses
    private var readySent = false

    // Whether a base layer loaded notification has been sent to subclasses
    private var imageLoadedSent = false

    // Event listener
    private var onImageEventListener: OnImageEventListener? = null

    // Scale and center listener
    private var onStateChangedListener: OnStateChangedListener? = null

    // Long click listener
    private var _onLongClickListener: OnLongClickListener? = null

    // Long click handler
    private val _handler: Handler

    // Paint objects created once and reused for efficiency
    private var bitmapPaint: Paint? = null
    private var debugTextPaint: Paint? = null
    private var debugLinePaint: Paint? = null
    private var tileBgPaint: Paint? = null

    // Volatile fields used to reduce object creation
    private var satTemp: ScaleAndTranslate? = null
    private var _matrix: Matrix? = null
    private var sRect: RectF? = null
    private val srcArray = FloatArray(8)
    private val dstArray = FloatArray(8)

    //The logical density of the display
    private val density: Float

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(
        context,
        attrs,
        defStyleAttr,
        0
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        density = resources.displayMetrics.density
        setMinimumDpi(160)
        setDoubleTapZoomDpi(160)
        setMinimumTileDpi(320)
        setGestureDetector(context)
        this._handler = Handler(object : Handler.Callback {
            override fun handleMessage(msg: Message): Boolean {
                if (msg.what == MESSAGE_LONG_CLICK) {
                    _onLongClickListener?.let {
                        maxTouchCount = 0
                        this@SubsamplingScaleImageView.setOnLongClickListener(it)
                        performLongClick()
                        this@SubsamplingScaleImageView.setOnLongClickListener(null)
                    }
                }
                return true
            }
        })
        // Handle XML attributes
        attrs?.let {
            val typedAttr =
                context.obtainStyledAttributes(attrs, R.styleable.SubsamplingScaleImageView)
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_assetName)) {
                val assetName = typedAttr.getString(R.styleable.SubsamplingScaleImageView_assetName)
                if (assetName != null && assetName.length > 0) {
                    setImage(ImageSource.asset(assetName).tilingEnable())
                }
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_src)) {
                val resId = typedAttr.getResourceId(R.styleable.SubsamplingScaleImageView_src, 0)
                if (resId > 0) {
                    setImage(ImageSource.resource(resId).tilingEnable())
                }
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_panEnabled)) {
                setPanEnabled(
                    typedAttr.getBoolean(
                        R.styleable.SubsamplingScaleImageView_panEnabled,
                        true
                    )
                );
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_zoomEnabled)) {
                zoomEnabled =
                    typedAttr.getBoolean(R.styleable.SubsamplingScaleImageView_zoomEnabled, true)
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_quickScaleEnabled)) {
                quickScaleEnabled = typedAttr.getBoolean(
                    R.styleable.SubsamplingScaleImageView_quickScaleEnabled,
                    true
                )
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_tileBackgroundColor)) {
                setTileBackgroundColor(
                    typedAttr.getColor(
                        R.styleable.SubsamplingScaleImageView_tileBackgroundColor,
                        Color.argb(0, 0, 0, 0)
                    )
                )
            }
            typedAttr.recycle()
        }

        quickScaleThreshold =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
    }


    /**
     * Set a solid color to render behind tiles, useful for displaying transparent PNGs.
     * @param tileBgColor Background color for tiles.
     */
    fun setTileBackgroundColor(tileBgColor: Int) {
        if (Color.alpha(tileBgColor) == 0) {
            tileBgPaint = null
        } else {
            tileBgPaint = Paint().apply {
                style = Paint.Style.FILL
                color = tileBgColor
            }
        }
        invalidate()
    }

    /**
     * Set the image source from a bitmap, resource, asset, file or other URI.
     * @param imageSource Image source.
     */
    fun setImage(imageSource: ImageSource) {
        setImage(imageSource, null, null)
    }

    /**
     * Set the image source from a bitmap, resource, asset, file or other URI, starting with a given orientation
     * setting, scale and center. This is the best method to use when you want scale and center to be restored
     * after screen orientation change; it avoids any redundant loading of tiles in the wrong orientation.
     * @param imageSource Image source.
     * @param state State to be restored. Nullable.
     */
    fun setImage(imageSource: ImageSource, state: ImageViewState) {
        setImage(imageSource, null, state)
    }

    /**
     * Set the image source from a bitmap, resource, asset, file or other URI, providing a preview image to be
     * displayed until the full size image is loaded.
     *
     * You must declare the dimensions of the full size image by calling {@link ImageSource#dimensions(int, int)}
     * on the imageSource object. The preview source will be ignored if you don't provide dimensions,
     * and if you provide a bitmap for the full size image.
     * @param imageSource Image source. Dimensions must be declared.
     * @param previewSource Optional source for a preview image to be displayed and allow interaction while the full size image loads.
     */
    fun setImage(imageSource: ImageSource, previewSource: ImageSource) {
        setImage(imageSource, previewSource, null)
    }

    fun setPanEnabled(panEnabled: Boolean) {
        this.panEnabled = panEnabled
        if (!panEnabled) {
            vTranslate?.let {
                it.x = width / 2 - (scale * (sWidth() / 2))
                it.y = height / 2 - (scale * (sHeight() / 2))
                if (isReady()) {
                    refreshRequiredTiles(true)
                    invalidate()
                }
            }
        }
    }

    /**
     * Loads the optimum tiles for display at the current scale and translate, so the screen can be filled with tiles
     * that are at least as high resolution as the screen. Frees up bitmaps that are now off the screen.
     * @param load Whether to load the new tiles needed. Use false while scrolling/panning for performance.
     */
    private fun refreshRequiredTiles(load: Boolean) {
        if (decoder == null || tileMap == null) return

        val sampleSize: Int = min(fullImageSampleSize, calculateInSampleSize(scale))

        // Load tiles of the correct sample size that are on screen. Discard tiles off screen, and those that are higher
        // resolution than required, or lower res than required but not the base layer, so the base layer is always present.
        for (tileMapEntry in tileMap!!.entries) {
            for (tile in tileMapEntry.value) {
                if (tile.sampleSize < sampleSize || (tile.sampleSize > sampleSize && tile.sampleSize != fullImageSampleSize)) {
                    tile.visible = false
                    tile.bitmap?.recycle()
                    tile.bitmap = null
                }
                if (tile.sampleSize == sampleSize) {
                    if (tileVisible(tile)) {
                        tile.visible = true
                        if (!tile.loading && tile.bitmap != null && load) {
                            val task = TileLoadTask(this, decoder, tile)
                            execute(task)
                        }
                    } else if (tile.sampleSize != fullImageSampleSize) {
                        tile.visible = false
                        tile.bitmap?.recycle()
                        tile.bitmap = null
                    }
                } else if (tile.sampleSize == fullImageSampleSize) {
                    tile.visible = true
                }
            }
        }
    }

    /**
     * Calculates sample size to fit the source image in given bounds.
     */
    private fun calculateInSampleSize(scale_: Float): Int {
        var scale = scale_
        if (minimumTileDpi > 0) {
            val metrics = resources.displayMetrics
            val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
            scale *= (minimumTileDpi / averageDpi)
        }

        val reqWidth = (sWidth() * scale).toInt()
        val reqHeight = (sHeight() * scale).toInt()

        // Raw height and width of image
        var inSampleSize = 1
        if (reqWidth == 0 || reqHeight == 0) {
            return 32
        }

        if (sHeight() > reqHeight || sWidth() > reqWidth) {
            // Calculate ratios of height and width to requested height and width
            val heightRatio = round(sHeight().toFloat() / reqHeight.toFloat()).toInt()
            val widthRatio = round(sWidth().toFloat() / reqWidth.toFloat()).toInt()

            // Choose the smallest ratio as inSampleSize value, this will guarantee
            // a final image with both dimensions larger than or equal to the
            // requested height and width.
            inSampleSize = min(heightRatio, widthRatio)
        }

        // We want the actual sample size that will be used, so round down to nearest power of 2.
        var power = 1
        while (power * 2 < inSampleSize) {
            power *= 2
        }
        return power
    }

    /**
     * Determine whether tile is visible.
     */
    private fun tileVisible(tile: Tile): Boolean {
        val sVisLeft: Float = viewToSourceX(0f)
        val sVisRight: Float = viewToSourceX(width.toFloat())
        val sVisTop: Float = viewToSourceY(0f)
        val sVisBottom: Float = viewToSourceY(height.toFloat())
        return !(sVisLeft > tile.sRect.right || tile.sRect.left > sVisRight || sVisTop > tile.sRect.bottom || tile.sRect.top > sVisBottom)
    }

    /**
     * Call to find whether the view is initialised, has dimensions, and will display an image on
     * the next draw. If a preview has been provided, it may be the preview that will be displayed
     * and the full size image may still be loading. If no preview was provided, this is called once
     * the base layer tiles of the full size image are loaded.
     * @return true if the view is ready to display an image and accept touch gestures.
     */
    fun isReady(): Boolean {
        return readySent
    }

    /**
     * Set the image source from a bitmap, resource, asset, file or other URI, providing a preview image to be
     * displayed until the full size image is loaded, starting with a given orientation setting, scale and center.
     * This is the best method to use when you want scale and center to be restored after screen orientation change;
     * it avoids any redundant loading of tiles in the wrong orientation.
     *
     * You must declare the dimensions of the full size image by calling {@link ImageSource#dimensions(int, int)}
     * on the imageSource object. The preview source will be ignored if you don't provide dimensions,
     * and if you provide a bitmap for the full size image.
     * @param imageSource Image source. Dimensions must be declared.
     * @param previewSource Optional source for a preview image to be displayed and allow interaction while the full size image loads.
     * @param state State to be restored. Nullable.
     */
    fun setImage(imageSource: ImageSource, previewSource: ImageSource?, state: ImageViewState?) {
        reset(true)
        if (state != null) restoreState(state)
        previewSource?.let {
            if (imageSource.bitmap != null) {
                throw IllegalArgumentException("Preview image cannot be used when a bitmap is provided for the main image")
            }
            if (imageSource.sWidth <= 0 || imageSource.sHeight <= 0) {
                throw IllegalArgumentException("Preview image cannot be used unless dimensions are provided for the main image")
            }
            this.sWidth = imageSource.sWidth
            this.sHeight = imageSource.sHeight
            this.pRegion = it.sRegion
            if (it.bitmap != null) {
                this.bitmapIsCached = it.cached
                onPreviewLoaded(it.bitmap)
            } else {
                var uri: Uri? = it.uri
                if (uri == null && it.resource != null) {
                    uri =
                        Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + it.resource)
                }
                val task = BitmapLoadTask(this, context, bitmapDecoderFactory, uri, true)
                execute(task)
            }
        }

        if (imageSource.bitmap != null) {
            onImageLoaded(
                Bitmap.createBitmap(
                    imageSource.bitmap,
                    imageSource.sRegion.left,
                    imageSource.sRegion.top,
                    imageSource.sRegion.width(),
                    imageSource.sRegion.height()
                ), ORIENTATION_0, false
            )
        } else {
            sRegion = imageSource.sRegion
            uri = imageSource.uri
            if (uri == null && imageSource.resource != null) {
                uri =
                    Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + imageSource.resource)
            }
            if (imageSource.title || sRegion != null) {
                // Load the bitmap using tile decoding.
                val task = TilesInitTask(this, context, regionDecoderFactory, uri!!)
                execute(task)
            } else {
                // Load the bitmap as a single image.
                val task = BitmapLoadTask(this, context, bitmapDecoderFactory, uri!!, false)
                execute(task)
            }
        }
    }

    /**
     * Set scale, center and orientation from saved state.
     */
    private fun restoreState(state: ImageViewState?) {
        if (state != null && VALID_ORIENTATIONS.contains(state.orientation)) {
            orientation = state.orientation
            pendingScale = state.scale
            sPendingCenter = state.getCenter()
            invalidate()
        }
    }

    /**
     * Reset all state before setting/changing image or setting new rotation.
     */
    private fun reset(newImage: Boolean) {
        debug("reset newImage = $newImage")
        scale = 0f
        scaleStart = 0f
        vTranslate = null
        vTranslateStart = null
        vTranslateBefore = null
        pendingScale = 0f
        sPendingCenter = null
        sRequestedCenter = null
        isZooming = false
        isPanning = false
        isQuickScaling = false
        maxTouchCount = 0
        fullImageSampleSize = 0
        vCenterStart = null
        vDistStart = 0f
        quickScaleLastDistance = 0f
        quickScaleMoved = false
        quickScaleSCenter = null
        quickScaleVLastPoint = null
        quickScaleVStart = null
        anim = null
        satTemp = null
        _matrix = null
        sRect = null
        if (newImage) {
            uri = null
            decoderLock.writeLock().lock()
            try {
                decoder?.recycle()
                decoder = null
            } finally {
                decoderLock.writeLock().unlock()
            }
            if (!bitmapIsCached) {
                bitmap?.recycle()
            }
            if (bitmap != null && bitmapIsCached) {
                onImageEventListener?.onPreviewReleased()
            }
            sWidth = 0
            sHeight = 0
            sOrientation = 0
            sRegion = null
            pRegion = null
            readySent = false
            imageLoadedSent = false
            bitmap = null
            bitmapIsPreview = false
            bitmapIsCached = false
        }
        tileMap?.let {
            for (tileMapEntry in it.entries) {
                for (tile in tileMapEntry.value) {
                    tile.visible = false
                    tile.bitmap?.recycle()
                    tile.bitmap = null
                }
            }
        }
        tileMap = null
        setGestureDetector(context)
    }

    private fun setGestureDetector(context: Context) {
        this.detector =
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent?,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (panEnabled && readySent && vTranslate != null && e1 != null && e2 != null && (abs(
                            e1.x - e2.x
                        ) > 50 || abs(e1.y - e2.y) > 50) && (abs(velocityX) > 500 || abs(velocityY) > 500) && !isZooming
                    ) {
                        val vTranslateEnd: PointF = PointF(
                            vTranslate!!.x + velocityX * 0.25f,
                            vTranslate!!.y + velocityY * 0.25f
                        )
                        val sCenterXEnd: Float = (width / 2 - vTranslateEnd.x / scale)
                        val sCenterYEnd: Float = (height / 2 - vTranslateEnd.y) / scale
                        AnimationBuilder(PointF(sCenterXEnd, sCenterYEnd)).withEasing(EASE_OUT_QUAD)
                            .withPanLimited(false).withOrigin(ORIGIN_FLING).start()
                        return true
                    }
                    return super.onFling(e1, e2, velocityX, velocityY)
                }

                override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                    performClick()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (zoomEnabled && readySent && vTranslate != null) {
                        // Hacky solution for #15 - after a double tap the GestureDetector gets in a state
                        // where the next fling is ignored, so here we replace it with a new one.
                        setGestureDetector(context)
                        if (quickScaleEnabled) {
                            // Store quick scale params. This will become either a double tap zoom or a
                            // quick scale depending on whether the user swipes.
                            vCenterStart = PointF(e.x, e.y)
                            vTranslateStart = PointF(vTranslate!!.x, vTranslate!!.y)
                            scaleStart = scale
                            isQuickScaling = true
                            isZooming = true
                            quickScaleLastDistance = -1f
                            quickScaleSCenter = viewToSourceCoord(vCenterStart!!)
                            quickScaleVStart = PointF(e.x, e.y)
                            quickScaleVLastPoint =
                                PointF(quickScaleSCenter?.x ?: 0f, quickScaleSCenter?.y ?: 0f)
                            quickScaleMoved = false
                            // We need to get events in onTouchEvent after this.
                            return false
                        } else {
                            // Start double tap zoom animation.
                            doubleTapZoom(
                                viewToSourceCoord(PointF(e.x, e.y)) ?: PointF(e.x, e.y),
                                PointF(e.x, e.y)
                            )
                            return true
                        }
                    }
                    return super.onDoubleTap(e)
                }
            })

        singleDetector =
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                    performClick()
                    return true
                }
            })
    }

    /**
     * Double tap zoom handler triggered from gesture detector or on touch, depending on whether
     * quick scale is enabled.
     */
    private fun doubleTapZoom(sCenter: PointF, vFocus: PointF) {
        if (!panEnabled) {
            if (sRequestedCenter != null) {
                // With a center specified from code, zoom around that point.
                sCenter.x = sRequestedCenter!!.x
                sCenter.y = sRequestedCenter!!.y
            } else {
                // With no requested center, scale around the image center.
                sCenter.x = (sWidth() / 2).toFloat()
                sCenter.y = (sHeight() / 2).toFloat()
            }
        }
        val doubleTapZoomScale = Math.min(maxScale, doubleTapZoomScale)
        val zoomIn = scale <= doubleTapZoomScale * 0.9 || scale == minScale
        val targetScale = if (zoomIn) doubleTapZoomScale else minScale()
        if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER_IMMEDIATE) {
            setScaleAndCenter(targetScale, sCenter)
        } else if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER || !zoomIn || !panEnabled) {
            AnimationBuilder(targetScale, sCenter).withInterruptible(false)
                .withDuration(doubleTapZoomDuration.toLong()).withOrigin(
                    ORIGIN_DOUBLE_TAP_ZOOM
                ).start()
        } else if (doubleTapZoomStyle == ZOOM_FOCUS_FIXED) {
            AnimationBuilder(targetScale, sCenter, vFocus).withInterruptible(false)
                .withDuration(doubleTapZoomDuration.toLong()).withOrigin(
                    ORIGIN_DOUBLE_TAP_ZOOM
                ).start()
        }
        invalidate()
    }

    /**
     * Externally change the scale and translation of the source image. This may be used with getCenter() and getScale()
     * to restore the scale and zoom after a screen rotate.
     * @param scale New scale to set.
     * @param sCenter New source image coordinate to center on the screen, subject to boundaries.
     */
    fun setScaleAndCenter(scale: Float, sCenter: PointF?) {
        anim = null
        pendingScale = scale
        sPendingCenter = sCenter
        sRequestedCenter = sCenter
        invalidate()
    }

    private class Tile {
        var sRect: Rect = Rect()
        var sampleSize = 0
        var bitmap: Bitmap? = null
        var loading = false
        var visible = false

        // Volatile fields instantiated once then updated before use to reduce GC.
        var vRect: Rect? = null
        var fileSRect: Rect = Rect()
    }

    private class Anim {
        var scaleStart = 0f// Scale at start of anim

        var scaleEnd = 0f // Scale at end of anim (target)

        var sCenterStart: PointF? = null // Source center point at start

        var sCenterEnd: PointF? = null // Source center point at end, adjusted for pan limits

        var sCenterEndRequested: PointF? =
            null // Source center point that was requested, without adjustment

        var vFocusStart: PointF? = null // View point that was double tapped

        var vFocusEnd: PointF? =
            null // Where the view focal point should be moved to during the anim

        var duration: Long = 500 // How long the anim takes

        var interruptible = true // Whether the anim can be interrupted by a touch

        var easing = EASE_IN_OUT_QUAD // Easing style

        var origin = ORIGIN_ANIM // Animation origin (API, double tap or fling)

        var time = System.currentTimeMillis() // Start time

        var listener: OnAnimationEventListener? = null // Event listener

    }

    /**
     * Async task used to get image details without blocking the UI thread.
     */
    private class TilesInitTask : AsyncTask<Unit, Unit, IntArray?> {

        private val viewRef: WeakReference<SubsamplingScaleImageView>
        private val contextRef: WeakReference<Context>
        private val decoderFactoryRef: WeakReference<DecoderFactory<out ImageRegionDecoder>>
        private val source: Uri
        private var decoder: ImageRegionDecoder? = null
        private var exception: Exception? = null

        constructor(
            view: SubsamplingScaleImageView,
            context: Context,
            decoderFactory: DecoderFactory<out ImageRegionDecoder>,
            source: Uri
        ) {
            this.viewRef = WeakReference(view)
            this.contextRef = WeakReference(context)
            this.decoderFactoryRef = WeakReference(decoderFactory)
            this.source = source
        }

        override fun doInBackground(vararg params: Unit?): IntArray? {
            try {
                val sourceUri: String = source.toString()
                val context: Context? = contextRef.get()
                val decoderFactory: DecoderFactory<out ImageRegionDecoder>? =
                    decoderFactoryRef.get()
                val view: SubsamplingScaleImageView? = viewRef.get()
                if (context != null && decoderFactory != null && view != null) {
                    view.debug("TilesInitTask.doInBackground")
                    decoder = decoderFactory.make()
                    val dimensions = decoder!!.init(context, source)
                    var sWidth = dimensions.x
                    var sHeight = dimensions.y
                    val exifOrientation = view.getExifOrientation(context, sourceUri)
                    view.sRegion?.let {
                        it.left = max(0, it.left)
                        it.top = max(0, it.top)
                        it.right = max(0, it.right)
                        it.bottom = max(0, it.bottom)
                        sWidth = it.width()
                        sHeight = it.height()
                    }
                    return intArrayOf(sWidth, sHeight, exifOrientation)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialise bitmap decoder", e)
                this.exception = e
            }
            return null
        }

        override fun onPostExecute(xyo: IntArray?) {
            val view = viewRef.get()
            view?.let {
                if (decoder != null && xyo != null && xyo.size == 3) {
                    it.onTilesInited(decoder!!, xyo[0], xyo[1], xyo[2])
                } else if (exception != null) {
                    it.onImageEventListener?.onImageLoadError(exception)
                }
            }
        }

    }

    /**
     * Called by worker task when decoder is ready and image size and EXIF orientation is known.
     */
    @Synchronized
    private fun onTilesInited(
        decoder: ImageRegionDecoder,
        sWidth: Int,
        sHeight: Int,
        sOrientation: Int
    ) {
        debug("onTilesInited sWidth=%d, sHeight=%d, sOrientation=%d", sWidth, sHeight, orientation)
        if (this.sWidth > 0 && this.sHeight > 0 && (this.sWidth != sWidth || this.sHeight != sHeight)) {
            reset(false)
            bitmap?.let {
                if (!bitmapIsCached) {
                    it.recycle()
                }
                bitmap = null
                if (bitmapIsCached) {
                    onImageEventListener?.onPreviewReleased()
                }
                bitmapIsPreview = false
                bitmapIsCached = false
            }
        }
        this.decoder = decoder
        this.sWidth = sWidth
        this.sHeight = sHeight
        this.sOrientation = sOrientation
        checkReady()
        if (!checkImageLoaded() && maxTileWidth > 0 && maxTileWidth != TILE_SIZE_AUTO && maxTileHeight > 0 && maxTileHeight != TILE_SIZE_AUTO && width > 0 && height > 0) {
            initialiseBaseLayer(Point(maxTileWidth, maxTileHeight))
        }
        invalidate()
        requestLayout()
    }

    /**
     * Called on first draw when the view has dimensions. Calculates the initial sample size and starts async loading of
     * the base layer image - the whole source subsampled as necessary.
     */
    @Synchronized
    private fun initialiseBaseLayer(maxTileDimensions: Point) {
        debug(
            "initialiseBaseLayer maxTileDimensions=%dx%d",
            maxTileDimensions.x,
            maxTileDimensions.y
        )
        satTemp = ScaleAndTranslate(0f, PointF(0f, 0f))
        fitToBounds(true, satTemp!!)

        // Load double resolution - next level will be split into four tiles and at the center all four are required,
        // so don't bother with tiling until the next level 16 tiles are needed.
        fullImageSampleSize = calculateInSampleSize(satTemp!!.scale)
        if (fullImageSampleSize > 1) {
            fullImageSampleSize /= 2
        }

        if (fullImageSampleSize == 1 && sRegion == null && sWidth() < maxTileDimensions.x && sHeight() < maxTileDimensions.y) {
            // Whole image is required at native resolution, and is smaller than the canvas max bitmap size.
            // Use BitmapDecoder for better image support.
            decoder?.recycle()
            decoder = null
            val task = BitmapLoadTask(this, context, bitmapDecoderFactory, uri, false)
            execute(task)
        } else {
            initialiseTileMap(maxTileDimensions)
            tileMap?.get(fullImageSampleSize)?.let {
                for (baseTile in it) {
                    val task = TileLoadTask(this, decoder, baseTile)
                    execute(task)
                }
            }
            refreshRequiredTiles(true)
        }
    }

    /**
     * Once source image and view dimensions are known, creates a map of sample size to tile grid.
     */
    private fun initialiseTileMap(maxTileDimensions: Point) {
        debug(
            "initialiseTileMap maxTileDimensions=%dx%d",
            maxTileDimensions.x,
            maxTileDimensions.y
        );
        this.tileMap = LinkedHashMap()
        var sampleSize = fullImageSampleSize
        var xTiles = 1
        var yTiles = 1
        while (true) {
            var sTileWidth = sWidth() / xTiles
            var sTileHeight = sHeight() / yTiles
            var subTileWidth = sTileWidth / sampleSize
            var subTileHeight = sTileHeight / sampleSize
            while (subTileWidth + xTiles + 1 > maxTileDimensions.x || (subTileWidth > getWidth() * 1.25 && sampleSize < fullImageSampleSize)) {
                xTiles += 1;
                sTileWidth = sWidth() / xTiles;
                subTileWidth = sTileWidth / sampleSize;
            }
            while (subTileHeight + yTiles + 1 > maxTileDimensions.y || (subTileHeight > getHeight() * 1.25 && sampleSize < fullImageSampleSize)) {
                yTiles += 1;
                sTileHeight = sHeight() / yTiles;
                subTileHeight = sTileHeight / sampleSize;
            }
            val tileGrid = ArrayList<Tile>(xTiles * yTiles)
            for (x in 0 until xTiles) {
                for (y in 0 until yTiles) {
                    val tile = Tile()
                    tile.sampleSize = sampleSize
                    tile.visible = sampleSize == fullImageSampleSize
                    tile.sRect = Rect(
                        x * sTileWidth,
                        y * sTileHeight,
                        if (x == xTiles - 1) sWidth() else (x + 1) * sTileWidth,
                        if (y == yTiles - 1) sHeight() else (y + 1) * sTileHeight
                    )
                    tile.vRect = Rect(0, 0, 0, 0)
                    tile.fileSRect = Rect(tile.sRect)
                    tileGrid.add(tile)
                }
            }
            tileMap?.put(sampleSize, tileGrid)
            if (sampleSize == 1) {
                break
            } else {
                sampleSize /= 2
            }
        }
    }

    private fun execute(asyncTask: AsyncTask<Unit, Unit, *>) {
        asyncTask.executeOnExecutor(executor)
    }

    /**
     * Get source width taking rotation into account.
     */
    private fun sWidth(): Int {
        val rotation: Int = getRequiredRotation()
        if (rotation == 90 || rotation == 270) {
            return sHeight
        } else {
            return sWidth
        }
    }

    /**
     * Get source height taking rotation into account.
     */
    private fun sHeight(): Int {
        val rotation: Int = getRequiredRotation()
        if (rotation == 90 || rotation == 270) {
            return sWidth
        } else {
            return sHeight
        }
    }

    /**
     * Check whether either the full size bitmap or base layer tiles are loaded. First time, send image
     * loaded event to listener.
     */
    private fun checkImageLoaded(): Boolean {
        val imageLoaded: Boolean = isBaseLayerReady()
        if (!imageLoadedSent && imageLoaded) {
            preDraw()
            imageLoadedSent = true
            onImageLoaded()
            onImageEventListener?.onImageLoaded()
        }
        return imageLoaded
    }

    /**
     * Called once when the full size image or its base layer tiles have been loaded.
     */
    protected fun onImageLoaded() {

    }

    private fun checkReady(): Boolean {
        val ready =
            width > 0 && height > 0 && sWidth > 0 && sHeight > 0 && (bitmap != null || isBaseLayerReady())
        if (!readySent && ready) {
            preDraw()
            readySent = true
            onReady()
            onImageEventListener?.onReady()
        }
        return ready
    }

    /**
     * Sets scale and translate ready for the next draw.
     */
    private fun preDraw() {
        if (width == 0 || height == 0 || sWidth <= 0 || sHeight <= 0) {
            return
        }
        // If waiting to translate to new center position, set translate now
        if (sPendingCenter != null && pendingScale != null) {
            scale = pendingScale!!
            if (vTranslate == null) {
                vTranslate = PointF()
            }
            vTranslate!!.x = width / 2 - scale * sPendingCenter!!.x
            vTranslate!!.y = height / 2 - scale * sPendingCenter!!.y
            sPendingCenter = null
            pendingScale = null
            fitToBounds(true)
            refreshRequiredTiles(true)
        }

        // On first display of base image set up position, and in other cases make sure scale is correct.
        fitToBounds(false)
    }

    /**
     * Adjusts hypothetical future scale and translate values to keep scale within the allowed range and the image on screen. Minimum scale
     * is set so one dimension fills the view and the image is centered on the other dimension. Used to calculate what the target of an
     * animation should be.
     * @param center Whether the image should be centered in the dimension it's too small to fill. While animating this can be false to avoid changes in direction as bounds are reached.
     * @param sat The scale we want and the translation we're aiming for. The values are adjusted to be valid.
     */
    private fun fitToBounds(center_: Boolean, sat: ScaleAndTranslate) {
        var center = center_
        if (panLimit == PAN_LIMIT_OUTSIDE && isReady()) {
            center = false
        }

        val vTranslate: PointF = sat.vTranslate
        val scale: Float = limitedScale(sat.scale)
        val scaleWidth = scale * sWidth()
        val scaleHeight = scale * sHeight()

        if (panLimit == PAN_LIMIT_CENTER && isReady()) {
            vTranslate.x = max(vTranslate.x, width / 2 - scaleWidth)
            vTranslate.y = max(vTranslate.y, height / 2 - scaleHeight)
        } else if (center) {
            vTranslate.x = max(vTranslate.x, width - scaleWidth)
            vTranslate.y = max(vTranslate.y, height - scaleHeight)
        } else {
            vTranslate.x = max(vTranslate.x, -scaleWidth)
            vTranslate.y = max(vTranslate.y, -scaleHeight)
        }

        // Asymmetric padding adjustments
        val xPaddingRatio =
            if (paddingLeft > 0 || paddingRight > 0) paddingLeft.toFloat() / (paddingLeft + paddingRight).toFloat() else 0.5f
        val yPaddingRatio =
            if (paddingTop > 0 || paddingBottom > 0) paddingTop.toFloat() / (paddingTop + paddingBottom).toFloat() else 0.5f

        var maxTx = 0f
        var maxTy = 0f
        if (panLimit == PAN_LIMIT_CENTER && isReady()) {
            maxTx = max(0f, width.toFloat() / 2)
            maxTy = max(0f, height.toFloat() / 2)
        } else if (center) {
            maxTx = max(0f, (width - scaleWidth) * xPaddingRatio)
            maxTy = max(0f, (height - scaleHeight) * yPaddingRatio)
        } else {
            maxTx = max(0f, width.toFloat())
            maxTy = max(0f, height.toFloat())
        }

        vTranslate.x = min(vTranslate.x, maxTx)
        vTranslate.y = min(vTranslate.y, maxTy)

        sat.scale = scale
    }

    /**
     * Adjusts current scale and translate values to keep scale within the allowed range and the image on screen. Minimum scale
     * is set so one dimension fills the view and the image is centered on the other dimension.
     * @param center Whether the image should be centered in the dimension it's too small to fill. While animating this can be false to avoid changes in direction as bounds are reached.
     */
    private fun fitToBounds(center: Boolean) {
        var init = false
        if (vTranslate == null) {
            init = true
            vTranslate = PointF(0f, 0f)
        }
        if (satTemp == null) {
            satTemp = ScaleAndTranslate(0f, PointF(0f, 0f))
        }
        satTemp?.let {
            it.scale = scale
            it.vTranslate.set(vTranslate!!)
            fitToBounds(center, it)
            scale = it.scale
            vTranslate?.set(it.vTranslate)
            if (init && minimumScaleType != SCALE_TYPE_START) {
                vTranslate!!.set(
                    vTranslateForSCenter(
                        (sWidth() / 2).toFloat(),
                        (sHeight() / 2).toFloat(),
                        scale
                    )
                );
            }
        }
    }

    /**
     * Get the translation required to place a given source coordinate at the center of the screen, with the center
     * adjusted for asymmetric padding. Accepts the desired scale as an argument, so this is independent of current
     * translate and scale. The result is fitted to bounds, putting the image point as near to the screen center as permitted.
     */
    private fun vTranslateForSCenter(sCenterX: Float, sCenterY: Float, scale: Float): PointF {
        val vxCenter = paddingLeft + (width - paddingRight - paddingLeft) / 2
        val vyCenter = paddingTop + (height - paddingBottom - paddingTop) / 2
        if (satTemp == null) {
            satTemp = ScaleAndTranslate(0f, PointF(0f, 0f))
        }
        satTemp!!.scale = scale
        satTemp!!.vTranslate.set(vxCenter - (sCenterX * scale), vyCenter - (sCenterY * scale))
        fitToBounds(true, satTemp!!)
        return satTemp!!.vTranslate
    }

    /**
     * Returns the source point at the center of the view.
     * @return the source coordinates current at the center of the view.
     */
    fun getCenter(): PointF {
        val mX = width / 2
        val mY = height / 2
        return viewToSourceCoord(mX.toFloat(), mY.toFloat()) ?: PointF()
    }

    /**
     * Returns the minimum allowed scale.
     */
    private fun minScale(): Float {
        val vPadding = paddingBottom + paddingTop
        val hPadding = paddingLeft + paddingRight
        return if (minimumScaleType == SCALE_TYPE_CENTER_CROP || minimumScaleType == SCALE_TYPE_START) {
            Math.max(
                (width - hPadding) / sWidth().toFloat(),
                (height - vPadding) / sHeight().toFloat()
            )
        } else if (minimumScaleType == SCALE_TYPE_CUSTOM && minScale > 0) {
            minScale
        } else {
            Math.min(
                (width - hPadding) / sWidth().toFloat(),
                (height - vPadding) / sHeight().toFloat()
            )
        }
    }

    /**
     * Adjust a requested scale to be within the allowed limits.
     */
    private fun limitedScale(targetScale_: Float): Float {
        var targetScale = targetScale_
        targetScale = Math.max(minScale(), targetScale)
        targetScale = Math.min(maxScale, targetScale)
        return targetScale
    }

    /**
     * Given a requested source center and scale, calculate what the actual center will have to be to keep the image in
     * pan limits, keeping the requested center as near to the middle of the screen as allowed.
     */
    private fun limitedSCenter(
        sCenterX: Float,
        sCenterY: Float,
        scale: Float,
        sTarget: PointF
    ): PointF {
        val vTranslate = vTranslateForSCenter(sCenterX, sCenterY, scale)
        val vxCenter = paddingLeft + (width - paddingRight - paddingLeft) / 2
        val vyCenter = paddingTop + (height - paddingBottom - paddingTop) / 2
        val sx = (vxCenter - vTranslate.x) / scale
        val sy = (vyCenter - vTranslate.y) / scale
        sTarget[sx] = sy
        return sTarget
    }

    /**
     * Convert source coordinate to view coordinate.
     * @param sxy source coordinates to convert.
     * @return view coordinates.
     */
    fun sourceToViewCoord(sxy: PointF): PointF? {
        return sourceToViewCoord(sxy.x, sxy.y, PointF())
    }

    /**
     * Convert source coordinate to view coordinate.
     * @param sx source X coordinate.
     * @param sy source Y coordinate.
     * @return view coordinates.
     */
    fun sourceToViewCoord(sx: Float, sy: Float): PointF? {
        return sourceToViewCoord(sx, sy, PointF())
    }

    /**
     * Convert source coordinate to view coordinate.
     * @param sx source X coordinate.
     * @param sy source Y coordinate.
     * @param vTarget target object for result. The same instance is also returned.
     * @return view coordinates. This is the same instance passed to the vTarget param.
     */
    fun sourceToViewCoord(sx: Float, sy: Float, vTarget: PointF): PointF? {
        if (vTranslate == null) {
            return null
        }
        vTarget[sourceToViewX(sx)] = sourceToViewY(sy)
        return vTarget
    }

    /**
     * Convert source coordinate to view coordinate.
     * @param sxy source coordinates to convert.
     * @param vTarget target object for result. The same instance is also returned.
     * @return view coordinates. This is the same instance passed to the vTarget param.
     */
    fun sourceToViewCoord(sxy: PointF, vTarget: PointF): PointF? {
        return sourceToViewCoord(sxy.x, sxy.y, vTarget)
    }


    /**
     * Builder class used to set additional options for a scale animation. Create an instance using {@link #animateScale(float)},
     * then set your options and call {@link #start()}.
     */
    private inner class AnimationBuilder {

        private val targetScale: Float
        private val targetSCenter: PointF
        private val vFocus: PointF?
        private var duration: Long = 500
        private var easing = EASE_IN_OUT_QUAD
        private var origin = ORIGIN_ANIM
        private var interruptible = true
        private var panLimited = true
        private var listener: OnAnimationEventListener? = null

        constructor(sCenter: PointF) {
            this.targetScale = scale
            this.targetSCenter = sCenter
            this.vFocus = null
        }

        constructor(scale: Float) {
            this.targetScale = scale
            this.targetSCenter = getCenter()
            this.vFocus = null
        }

        constructor(scale: Float, sCenter: PointF) {
            this.targetScale = scale
            this.targetSCenter = sCenter
            this.vFocus = null
        }

        constructor(scale: Float, sCenter: PointF, vFocus: PointF?) {
            this.targetScale = scale
            this.targetSCenter = sCenter
            this.vFocus = vFocus
        }

        /**
         * Desired duration of the anim in milliseconds. Default is 500.
         * @param duration duration in milliseconds.
         * @return this builder for method chaining.
         */
        fun withDuration(duration: Long): AnimationBuilder {
            this.duration = duration
            return this
        }

        /**
         * Whether the animation can be interrupted with a touch. Default is true.
         * @param interruptible interruptible flag.
         * @return this builder for method chaining.
         */
        fun withInterruptible(interruptible: Boolean): AnimationBuilder {
            this.interruptible = interruptible
            return this
        }

        /**
         * Set the easing style. See static fields. {@link #EASE_IN_OUT_QUAD} is recommended, and the default.
         * @param easing easing style.
         * @return this builder for method chaining.
         */
        fun withEasing(easing: Int): AnimationBuilder {
            require(VALID_EASING_STYLES.contains(easing)) { "Unknown easing type: $easing" }
            this.easing = easing
            return this
        }

        /**
         * Add an animation event listener.
         * @param listener The listener.
         * @return this builder for method chaining.
         */
        fun withOnAnimationEventListener(listener: OnAnimationEventListener?): AnimationBuilder {
            this.listener = listener
            return this
        }

        /**
         * Only for internal use. When set to true, the animation proceeds towards the actual end point - the nearest
         * point to the center allowed by pan limits. When false, animation is in the direction of the requested end
         * point and is stopped when the limit for each axis is reached. The latter behaviour is used for flings but
         * nothing else.
         */
        fun withPanLimited(panLimited: Boolean): AnimationBuilder {
            this.panLimited = panLimited
            return this
        }

        /**
         * Only for internal use. Indicates what caused the animation.
         */
        fun withOrigin(origin: Int): AnimationBuilder {
            this.origin = origin
            return this
        }

        /**
         * Starts the animation.
         */
        fun start() {
            try {
                anim?.listener?.onInterruptedByNewAnim()
            } catch (e: java.lang.Exception) {
                Log.w(TAG, "Error thrown by animation listener", e)
            }

            val vxCenter = paddingLeft + (width - paddingRight - paddingLeft) / 2
            val vyCenter = paddingTop + (height - paddingBottom - paddingTop) / 2
            val targetScale: Float = limitedScale(targetScale)
            val targetSCenter = if (panLimited) limitedSCenter(
                targetSCenter.x,
                targetSCenter.y,
                targetScale,
                PointF()
            ) else targetSCenter

            anim = Anim()
            anim?.let {
                it.scaleStart = scale
                it.scaleEnd = targetScale
                it.time = System.currentTimeMillis()
                it.sCenterEndRequested = targetSCenter
                it.sCenterStart = getCenter()
                it.sCenterEnd = targetSCenter
                it.vFocusStart = sourceToViewCoord(targetSCenter)
                it.vFocusEnd = PointF(vxCenter.toFloat(), vyCenter.toFloat())
                it.duration = duration
                it.interruptible = interruptible
                it.easing = easing
                it.origin = origin
                it.time = System.currentTimeMillis()
                it.listener = listener
            }

            vFocus?.let {
                // Calculate where translation will be at the end of the anim
                val vTranslateXEnd = it.x - targetScale * anim!!.sCenterStart!!.x
                val vTranslateYEnd = it.y - targetScale * anim!!.sCenterStart!!.y
                val satEnd = ScaleAndTranslate(targetScale, PointF(vTranslateXEnd, vTranslateYEnd))
                // Fit the end translation into bounds
                fitToBounds(true, satEnd)
                // Adjust the position of the focus point at end so image will be in bounds
                anim!!.vFocusEnd = PointF(
                    vFocus.x + (satEnd.vTranslate.x - vTranslateXEnd),
                    vFocus.y + (satEnd.vTranslate.y - vTranslateYEnd)
                )
            }

            invalidate()
        }

    }

    /**
     * An event listener for animations, allows events to be triggered when an animation completes,
     * is aborted by another animation starting, or is aborted by a touch event. Note that none of
     * these events are triggered if the activity is paused, the image is swapped, or in other cases
     * where the view's internal state gets wiped or draw events stop.
     */
    interface OnAnimationEventListener {

        /**
         * The animation has completed, having reached its endpoint.
         */
        fun onComplete()

        /**
         * The animation has been aborted before reaching its endpoint because the user touched the screen.
         */
        fun onInterruptedByUser()

        /**
         * The animation has been aborted before reaching its endpoint because a new animation has been started.
         */
        fun onInterruptedByNewAnim()
    }

    /**
     * This is a screen density aware alternative to {@link #setMaxScale(float)}; it allows you to express the maximum
     * allowed scale in terms of the minimum pixel density. This avoids the problem of 1:1 scale still being
     * too small on a high density screen. A sensible starting point is 160 - the default used by this view.
     * @param dpi Source image pixel density at maximum zoom.
     */
    fun setMinimumDpi(dpi: Int) {
        val metrics = resources.displayMetrics
        val averageDpi: Float = (metrics.xdpi + metrics.ydpi) / 2.0f
        maxScale = averageDpi / dpi
    }

    /**
     * A density aware alternative to {@link #setDoubleTapZoomScale(float)}; this allows you to express the scale the
     * image will zoom in to when double tapped in terms of the image pixel density. Values lower than the max scale will
     * be ignored. A sensible starting point is 160 - the default used by this view.
     * @param dpi New value for double tap gesture zoom scale.
     */
    fun setDoubleTapZoomDpi(dpi: Int) {
        val metrics = resources.displayMetrics
        val averageDpi: Float = (metrics.xdpi + metrics.ydpi) / 2
        doubleTapZoomScale = averageDpi / dpi
    }

    /**
     * By default, image tiles are at least as high resolution as the screen. For a retina screen this may not be
     * necessary, and may increase the likelihood of an OutOfMemoryError. This method sets a DPI at which higher
     * resolution tiles should be loaded. Using a lower number will on average use less memory but result in a lower
     * quality image. 160-240dpi will usually be enough. This should be called before setting the image source,
     * because it affects which tiles get loaded. When using an untiled source image this method has no effect.
     * @param minimumTileDpi Tile loading threshold.
     */
    fun setMinimumTileDpi(minimumTileDpi: Int) {
        val metrics = resources.displayMetrics
        val averageDpi: Float = (metrics.xdpi + metrics.ydpi) / 2
        this.minimumTileDpi = min(averageDpi, minimumTileDpi.toFloat()).toInt()
        if (readySent) {
            reset(false)
            invalidate()
        }
    }

    /**
     * Convert screen coordinate to source coordinate.
     * @param vxy view X/Y coordinate.
     * @return a coordinate representing the corresponding source coordinate.
     */
    fun viewToSourceCoord(vxy: PointF): PointF? {
        return viewToSourceCoord(vxy.x, vxy.y, PointF())
    }

    /**
     * Convert screen to source x coordinate.
     */
    private fun viewToSourceX(vx: Float): Float {
        if (vTranslate == null) return Float.NaN
        return (vx - vTranslate!!.x) / scale
    }

    /**
     * Convert screen to source y coordinate.
     */
    private fun viewToSourceY(vy: Float): Float {
        if (vTranslate == null) return Float.NaN
        return (vy - vTranslate!!.y) / scale
    }

    /**
     * Convert screen coordinate to source coordinate.
     * @param vx view X coordinate.
     * @param vy view Y coordinate.
     * @return a coordinate representing the corresponding source coordinate.
     */
    fun viewToSourceCoord(vx: Float, vy: Float): PointF? {
        return viewToSourceCoord(vx, vy, PointF())
    }

    fun viewToSourceCoord(vx: Float, vy: Float, sTarget: PointF): PointF? {
        if (vTranslate == null) {
            return null
        }
        sTarget.set(viewToSourceX(vx), viewToSourceY(vy))
        return sTarget
    }

    /**
     * Convert source to view x coordinate.
     */
    private fun sourceToViewX(sx: Float): Float {
        if (vTranslate == null) return Float.NaN
        return sx * scale + vTranslate!!.x
    }

    /**
     * Convert source to view y coordinate.
     */
    private fun sourceToViewY(sy: Float): Float {
        return if (vTranslate == null) {
            Float.NaN
        } else sy * scale + vTranslate!!.y
    }

    /**
     * Debug logger
     */
    @AnyThread
    private fun debug(message: String, vararg args: Any?) {
        if (debug) {
            Log.d(TAG, String.format(message, args))
        }
    }

    /**
     * Helper method for load tasks. Examines the EXIF info on the image file to determine the orientation.
     * This will only work for external files, not assets, resources or other URIs.
     */
    @AnyThread
    private fun getExifOrientation(context: Context, sourceUri: String?): Int {
        var exifOrientation = ORIENTATION_0
        if (sourceUri?.startsWith(ContentResolver.SCHEME_CONTENT) == true) {
            var cursor: Cursor? = null
            try {
                val columns = arrayOf(MediaStore.Images.Media.ORIENTATION)
                cursor =
                    context.contentResolver.query(Uri.parse(sourceUri), columns, null, null, null)
                cursor?.let {
                    if (it.moveToFirst()) {
                        val orientation = cursor.getInt(0)
                        if (VALID_ORIENTATIONS.contains(orientation) && orientation != ORIENTATION_USE_EXIF) {
                            exifOrientation = orientation;
                        } else {
                            Log.w(TAG, "Unsupported orientation: $orientation");
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not get orientation of image from media store")
            } finally {
                cursor?.close()
            }

        } else if (sourceUri?.startsWith(ImageSource.FILE_SCHEME) == true) {
            try {
                val exifInterface =
                    ExifInterface(sourceUri.substring(ImageSource.FILE_SCHEME.length - 1))
                val orientationAttr = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                if (orientationAttr == ExifInterface.ORIENTATION_NORMAL || orientationAttr == ExifInterface.ORIENTATION_UNDEFINED) {
                    exifOrientation = ORIENTATION_0
                } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_90) {
                    exifOrientation = ORIENTATION_90
                } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_180) {
                    exifOrientation = ORIENTATION_180
                } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_270) {
                    exifOrientation = ORIENTATION_270
                } else {
                    Log.w(TAG, "Unsupported EXIF orientation: $orientationAttr")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not get EXIF orientation of image")
            }
        }
        return exifOrientation
    }

    /**
     * Async task used to load bitmap without blocking the UI thread.
     */
    private class BitmapLoadTask : AsyncTask<Unit, Unit, Int?> {

        private val viewRef: WeakReference<SubsamplingScaleImageView?>
        private val contextRef: WeakReference<Context?>
        private val decoderFactoryRef: WeakReference<DecoderFactory<out ImageDecoder>?>
        private val source: Uri?
        private val preview: Boolean
        private var bitmap: Bitmap? = null
        private var exception: Exception? = null

        constructor(
            view: SubsamplingScaleImageView?,
            context: Context?,
            decoderFactory: DecoderFactory<out ImageDecoder>?,
            source: Uri?,
            preview: Boolean
        ) {
            this.viewRef = WeakReference(view)
            this.contextRef = WeakReference(context)
            this.decoderFactoryRef = WeakReference(decoderFactory)
            this.source = source
            this.preview = preview
        }

        override fun doInBackground(vararg params: Unit?): Int? {
            try {
                val sourceUri: String = source.toString()
                val context: Context? = contextRef.get()
                val decoderFactory: DecoderFactory<out ImageDecoder?>? = decoderFactoryRef.get()
                val view = viewRef.get()
                if (context != null && decoderFactory != null && view != null && source != null) {
                    view.debug("BitmapLoadTask.doInBackground")
                    bitmap = decoderFactory.make()?.decode(context, source)
                    return view.getExifOrientation(context, sourceUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bitmap", e)
                this.exception = e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Failed to load bitmap - OutOfMemoryError", e)
                this.exception = RuntimeException(e)
            }
            return null
        }

        override fun onPostExecute(orientation: Int?) {
            val subsamplingScaleImageView = viewRef.get()
            subsamplingScaleImageView?.let {
                if (bitmap != null && orientation != null) {
                    if (preview) {
                        it.onPreviewLoaded(bitmap!!)
                    } else {
                        it.onImageLoaded(bitmap!!, orientation, false)
                    }
                } else if (exception != null) {
                    if (preview) {
                        it.onImageEventListener?.onPreviewLoadError(exception)
                    } else {
                        it.onImageEventListener?.onImageLoadError(exception)
                    }
                }
            }
        }
    }

    /**
     * Called by worker task when preview image is loaded.
     */
    private fun onPreviewLoaded(previewBitmap: Bitmap) {
        debug("onPreviewLoaded")
        if (bitmap != null || imageLoadedSent) {
            previewBitmap.recycle()
            return
        }
        if (pRegion != null) {
            bitmap = Bitmap.createBitmap(
                previewBitmap,
                pRegion!!.left,
                pRegion!!.top,
                pRegion!!.width(),
                pRegion!!.height()
            )
        } else {
            bitmap = previewBitmap
        }
        bitmapIsPreview = true
        if (checkReady()) {
            invalidate()
            requestLayout()
        }
    }

    /**
     * Called by worker task when full size image bitmap is ready (tiling is disabled).
     */
    @Synchronized
    private fun onImageLoaded(bitmap: Bitmap, sOrientation: Int, bitmapIsCached: Boolean) {
        debug("onImageLoaded")
        if (this.sWidth > 0 && this.sHeight > 0 && (this.sWidth != bitmap.width || this.sHeight != bitmap.height)) {
            reset(false)
        }
        if (this.bitmap != null && !this.bitmapIsCached) {
            this.bitmap?.recycle()
        }

        if (this.bitmap != null && this.bitmapIsCached) {
            onImageEventListener?.onPreviewReleased()
        }

        this.bitmapIsPreview = false
        this.bitmapIsCached = bitmapIsCached
        this.bitmap = bitmap
        this.sWidth = bitmap.width
        this.sHeight = bitmap.height
        this.sOrientation = sOrientation
        val ready = checkReady()
        val imageLoaded = checkImageLoaded()
        if (ready || imageLoaded) {
            invalidate()
            requestLayout()
        }
    }

    /**
     * An event listener, allowing activities to be notified of pan and zoom events. Initialisation
     * and calls made by your code do not trigger events; touch events and animations do. Methods in
     * this listener will be called on the UI thread and may be called very frequently - your
     * implementation should return quickly.
     */
    interface OnStateChangedListener {

        /**
         * The scale has changed. Use with {@link #getMaxScale()} and {@link #getMinScale()} to determine
         * whether the image is fully zoomed in or out.
         * @param newScale The new scale.
         * @param origin Where the event originated from - one of {@link #ORIGIN_ANIM}, {@link #ORIGIN_TOUCH}.
         */
        fun onScaleChanged(newScale: Float, origin: Int)

        /**
         * The source center has been changed. This can be a result of panning or zooming.
         * @param newCenter The new source center point.
         * @param origin Where the event originated from - one of {@link #ORIGIN_ANIM}, {@link #ORIGIN_TOUCH}.
         */
        fun onCenterChanged(newCenter: PointF, origin: Int)
    }

    private class ScaleAndTranslate(var scale: Float, val vTranslate: PointF)

    /**
     * Async task used to load images without blocking the UI thread.
     */
    private class TileLoadTask : AsyncTask<Unit, Unit, Bitmap?> {

        private val viewRef: WeakReference<SubsamplingScaleImageView>
        private val decoderRef: WeakReference<ImageRegionDecoder?>
        private val tileRef: WeakReference<Tile>
        private var exception: Exception? = null

        constructor(view: SubsamplingScaleImageView, decoder: ImageRegionDecoder?, tile: Tile) {
            this.viewRef = WeakReference(view)
            this.decoderRef = WeakReference(decoder)
            this.tileRef = WeakReference(tile)
            tile.loading = true
        }

        override fun doInBackground(vararg params: Unit?): Bitmap? {
            try {
                val view = viewRef.get()
                val decoder = decoderRef.get()
                val tile = tileRef.get()
                if (decoder != null && tile != null && view != null && decoder.isReady() && tile.visible) {
                    view.debug(
                        "TileLoadTask.doInBackground, tile.sRect=%s, tile.sampleSize=%d",
                        tile.sRect,
                        tile.sampleSize
                    );
                    view.decoderLock.readLock().lock()
                    try {
                        if (decoder.isReady()) {
                            // Update tile's file sRect according to rotation
                            view.fileSRect(tile.sRect, tile.fileSRect)
                            view.sRegion?.let {
                                tile.fileSRect?.offset(it.left, it.top)
                            }
                            return decoder.decodeRegion(tile.fileSRect, tile.sampleSize)
                        } else {
                            tile.loading = false
                        }
                    } finally {
                        view.decoderLock.readLock().unlock()
                    }
                } else if (tile != null) {
                    tile.loading = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode tile", e)
                this.exception = e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Failed to decode tile - OutOfMemoryError", e)
                this.exception = RuntimeException(e)
            }
            return null
        }

        override fun onPostExecute(bitmap: Bitmap?) {
            val subsamplingScaleImageView = viewRef.get()
            val tile = tileRef.get()
            if (subsamplingScaleImageView != null && tile != null) {
                if (bitmap != null) {
                    tile.bitmap = bitmap
                    tile.loading = false
                    subsamplingScaleImageView.onTileLoaded()
                } else if (exception != null) {
                    subsamplingScaleImageView.onImageEventListener?.onTileLoadError(exception)
                }
            }
        }

    }

    /**
     * Converts source rectangle from tile, which treats the image file as if it were in the correct orientation already,
     * to the rectangle of the image that needs to be loaded.
     */
    @AnyThread
    private fun fileSRect(sRect: Rect, target: Rect) {
        if (getRequiredRotation() == 0) {
            target.set(sRect)
        } else if (getRequiredRotation() == 90) {
            target.set(sRect.top, sHeight - sRect.right, sRect.bottom, sHeight - sRect.left)
        } else if (getRequiredRotation() == 180) {
            target.set(
                sWidth - sRect.right,
                sHeight - sRect.bottom,
                sWidth - sRect.left,
                sHeight - sRect.top
            )
        } else {
            target.set(sWidth - sRect.bottom, sRect.left, sWidth - sRect.top, sRect.right)
        }
    }

    private fun getRequiredRotation(): Int {
        return if (orientation == ORIENTATION_USE_EXIF) {
            sOrientation
        } else {
            orientation
        }
    }

    /**
     * Called by worker task when a tile has loaded. Redraws the view.
     */
    @Synchronized
    private fun onTileLoaded() {
        debug("onTileLoaded")
        checkReady()
        checkImageLoaded()
        if (isBaseLayerReady() && bitmap != null) {
            if (!bitmapIsCached) {
                bitmap?.recycle()
            }
            bitmap = null
            if (bitmapIsCached) {
                onImageEventListener?.onPreviewReleased()
            }
            bitmapIsPreview = false
            bitmapIsCached = false
        }
        invalidate()
    }

    /**
     * Checks whether the base layer of tiles or full size bitmap is ready.
     */
    private fun isBaseLayerReady(): Boolean {
        if (bitmap != null && !bitmapIsPreview) {
            return true
        } else if (tileMap != null) {
            var baseLayerReady = true
            for (tileMapEntry in tileMap!!.entries) {
                if (tileMapEntry.key == fullImageSampleSize) {
                    for (tile in tileMapEntry.value) {
                        if (tile.loading || tile.bitmap == null) {
                            baseLayerReady = false
                        }
                    }
                }
            }
            return baseLayerReady
        }
        return false
    }

    /**
     * An event listener, allowing subclasses and activities to be notified of significant events.
     */
    public interface OnImageEventListener {

        /**
         * Called when the dimensions of the image and view are known, and either a preview image,
         * the full size image, or base layer tiles are loaded. This indicates the scale and translate
         * are known and the next draw will display an image. This event can be used to hide a loading
         * graphic, or inform a subclass that it is safe to draw overlays.
         */
        fun onReady()

        /**
         * Called when the full size image is ready. When using tiling, this means the lowest resolution
         * base layer of tiles are loaded, and when tiling is disabled, the image bitmap is loaded.
         * This event could be used as a trigger to enable gestures if you wanted interaction disabled
         * while only a preview is displayed, otherwise for most cases [.onReady] is the best
         * event to listen to.
         */
        fun onImageLoaded()

        /**
         * Called when a preview image could not be loaded. This method cannot be relied upon; certain
         * encoding types of supported image formats can result in corrupt or blank images being loaded
         * and displayed with no detectable error. The view will continue to load the full size image.
         * @param e The exception thrown. This error is logged by the view.
         */
        fun onPreviewLoadError(e: Exception?)

        /**
         * Indicates an error initiliasing the decoder when using a tiling, or when loading the full
         * size bitmap when tiling is disabled. This method cannot be relied upon; certain encoding
         * types of supported image formats can result in corrupt or blank images being loaded and
         * displayed with no detectable error.
         * @param e The exception thrown. This error is also logged by the view.
         */
        fun onImageLoadError(e: Exception?)

        /**
         * Called when an image tile could not be loaded. This method cannot be relied upon; certain
         * encoding types of supported image formats can result in corrupt or blank images being loaded
         * and displayed with no detectable error. Most cases where an unsupported file is used will
         * result in an error caught by [.onImageLoadError].
         * @param e The exception thrown. This error is logged by the view.
         */
        fun onTileLoadError(e: Exception?)

        /**
         * Called when a bitmap set using ImageSource.cachedBitmap is no longer being used by the View.
         * This is useful if you wish to manage the bitmap after the preview is shown
         */
        fun onPreviewReleased()
    }

    /**
     * Called once when the view is initialised, has dimensions, and will display an image on the
     * next draw. This is triggered at the same time as {@link OnImageEventListener#onReady()} but
     * allows a subclass to receive this event without using a listener.
     */
    open fun onReady() {

    }

}