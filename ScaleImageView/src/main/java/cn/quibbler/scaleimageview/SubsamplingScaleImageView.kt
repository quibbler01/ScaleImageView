package cn.quibbler.scaleimageview

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.os.Message
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AnyThread
import cn.quibbler.scaleimageview.decoder.*
import java.util.*
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs
import kotlin.math.min

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

        private val VALID_ORIENTATIONS = listOf(ORIENTATION_0, ORIENTATION_90, ORIENTATION_180, ORIENTATION_270, ORIENTATION_USE_EXIF)

        /** During zoom animation, keep the point of the image that was tapped in the same place, and scale the image around it.  */
        const val ZOOM_FOCUS_FIXED = 1

        /** During zoom animation, move the point of the image that was tapped to the center of the screen.  */
        const val ZOOM_FOCUS_CENTER = 2

        /** Zoom in to and center the tapped point immediately without animating.  */
        const val ZOOM_FOCUS_CENTER_IMMEDIATE = 3

        private val VALID_ZOOM_STYLES = Arrays.asList(ZOOM_FOCUS_FIXED, ZOOM_FOCUS_CENTER, ZOOM_FOCUS_CENTER_IMMEDIATE)

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

        private val VALID_SCALE_TYPES = listOf(SCALE_TYPE_CENTER_CROP, SCALE_TYPE_CENTER_INSIDE, SCALE_TYPE_CUSTOM, SCALE_TYPE_START)

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
        private var preferredBitmapConfig: Bitmap.Config? = null
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
    private var fullImageSampleSize = 0

    private var tileMap: MutableMap<Intent, MutableList<Tile>>? = null

    // Overlay tile boundaries and other info
    private var debug = false

    // Image orientation setting
    private var orientation = ORIENTATION_0

    // Max scale allowed (prevent infinite zoom)
    private var maxScale: Float = 2f

    // Min scale allowed (prevent infinite zoom)
    private var minScale: Float = minScale()

    // Density to reach before loading higher resolution tiles
    private var minimumTileDpiv = -1

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
    private var zoomEnabled = true
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
    private var pendingScale: Float = 0f
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
    private val bitmapDecoderFactory: DecoderFactory<out ImageDecoder> = CompatDecoderFactory<ImageDecoder>(SkiaImageDecoder::class.java)
    private var regionDecoderFactory: DecoderFactory<out ImageRegionDecoder> = CompatDecoderFactory<ImageRegionDecoder>(SkiaImageRegionDecoder::class.java)


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
    private var onLongClickListener: OnLongClickListener? = null

    // Long click handler
    private val handler: Handler

    // Paint objects created once and reused for efficiency
    private var bitmapPaint: Paint? = null
    private var debugTextPaint: Paint? = null
    private var debugLinePaint: Paint? = null
    private var tileBgPaint: Paint? = null

    // Volatile fields used to reduce object creation
    private var satTemp: ScaleAndTranslate? = null
    private var matrix: Matrix? = null
    private var sRect: RectF? = null
    private val srcArray = FloatArray(8)
    private val dstArray = FloatArray(8)

    //The logical density of the display
    private val density: Float

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context, attrs, defStyleAttr, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        density = resources.displayMetrics.density
        setMinimumDpi(160)
        setDoubleTapZoomDpi(160)
        setMinimumTileDpi(320)
        setGestureDetector(context)
        this.handler = Handler(object : Handler.Callback {
            override fun handleMessage(msg: Message): Boolean {
                if (msg.what == MESSAGE_LONG_CLICK) {
                    onLongClickListener?.let {
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
            val typedAttr = context.obtainStyledAttributes(attrs, R.styleable.SubsamplingScaleImageView)
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_assetName)) {
                val assetName = typedAttr.getString(R.styleable.SubsamplingScaleImageView_assetName)
                if (assetName != null && assetName.length > 0) {
                    setImage(ImageSource.asset(assetName).tilingEnable())
                }
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_src)) {
                val resId = typedAttr.getResourceId(R.styleable.SubsamplingScaleImageView_src)
                if (resId > 0) {
                    setImage(ImageSource.resource(resId).tilingEnable())
                }
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_panEnabled)) {
                setPanEnabled(typedAttr.getBoolean(R.styleable.SubsamplingScaleImageView_panEnabled, true));
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_zoomEnabled)) {
                setZoomEnabled(typedAttr.getBoolean(R.styleable.SubsamplingScaleImageView_zoomEnabled, true));
            }
            if (typedAttr.hasValue(R.styleable.SubsamplingScaleImageView_quickScaleEnabled)) {
                setQuickScaleEnabled(typedAttr.getBoolean(R.styleable.SubsamplingScaleImageView_quickScaleEnabled, true));
            }
            if (typedAttr.hasValue(styleable.SubsamplingScaleImageView_tileBackgroundColor)) {
                setTileBackgroundColor(typedAttr.getColor(R.styleable.SubsamplingScaleImageView_tileBackgroundColor, Color.argb(0, 0, 0, 0)));
            }
            typedAttr.recycle()
        }

        quickScaleThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
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
                    uri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + it.resource)
                }
                val task = BitmapLoadTask(this, context, bitmapDecoderFactory, uri, true)
                execute(task)
            }
        }

        if (imageSource.bitmap != null && imageSource.sRegion != null) {
            onImageLoaded(Bitmap.createBitmap(imageSource.bitmap, imageSource.sRegion.left, imageSource.sRegion.top, imageSource.sRegion.width(), imageSource.sRegion.height()), ORIENTATION_0, false)
        } else if (imageSource.bitmap != null) {
            onImageLoaded(imageSource.bitmap, ORIENTATION_0, imageSource.cached)
        } else {
            sRegion = imageSource.sRegion
            uri = imageSource.uri
            if (uri == null && imageSource.resource != null) {
                uri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + imageSource.resource)
            }
            if (imageSource.title || sRegion != null) {
                // Load the bitmap using tile decoding.
                val task = TilesInitTask(this, context, regionDecoderFactory, uri)
                execute(task)
            } else {
                // Load the bitmap as a single image.
                val task = TilesInitTask(this, context, regionDecoderFactory, uri, false)
                execute(task)
            }
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
        matrix = null
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
        this.detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
                if (panEnabled && readySent && vTranslate != null && e1 != null && e2 != null && (abs(e1.x - e2.x) > 50 || abs(e1.y - e2.y) > 50) && (abs(velocityX) > 500 || abs(velocityY) > 500) && !isZooming) {
                    val vTranslateEnd: PointF = PointF(vTranslate!!.x + velocityX * 0.25f, vTranslate!!.y + velocityY * 0.25f)
                    val sCenterXEnd: Float = (width / 2 - vTranslateEnd.x / scale)
                    val sCenterYEnd: Float = (height / 2 - vTranslateEnd.y) / scale
                    AnimationBuilder(PointF(sCenterXEnd, sCenterYEnd)).withEasing(EASE_OUT_QUAD).withPanLimited(false).withOrigin(ORIGIN_FLING).start()
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
                        quickScaleVLastPoint = PointF(quickScaleSCenter.x, quickScaleSCenter.y)
                        quickScaleMoved = false
                        // We need to get events in onTouchEvent after this.
                        return false
                    } else {
                        // Start double tap zoom animation.
                        doubleTapZoom(viewToSourceCoord(PointF(e.x, e.y)), PointF(e.x, e.y))
                        return true
                    }
                }
                return super.onDoubleTap(e)
            }
        })

        singleDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                performClick()
                return true
            }
        })
    }

    private class Tile {
        var sRect: Rect? = null
        var sampleSize = 0
        var bitmap: Bitmap? = null
        var loading = false
        var visible = false

        // Volatile fields instantiated once then updated before use to reduce GC.
        var vRect: Rect? = null
        var fileSRect: Rect? = null
    }

    private class Anim {
        private val scaleStart = 0f// Scale at start of anim

        private val scaleEnd = 0f // Scale at end of anim (target)

        private val sCenterStart: PointF? = null // Source center point at start

        private val sCenterEnd: PointF? = null // Source center point at end, adjusted for pan limits

        private val sCenterEndRequested: PointF? = null // Source center point that was requested, without adjustment

        private val vFocusStart: PointF? = null // View point that was double tapped

        private val vFocusEnd: PointF? = null // Where the view focal point should be moved to during the anim

        private val duration: Long = 500 // How long the anim takes

        private val interruptible = true // Whether the anim can be interrupted by a touch

        private val easing = EASE_IN_OUT_QUAD // Easing style

        private val origin = ORIGIN_ANIM // Animation origin (API, double tap or fling)

        private val time = System.currentTimeMillis() // Start time

        private val listener: OnAnimationEventListener? = null // Event listener

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
        this.minimumTileDpiv = min(averageDpi, minimumTileDpi.toFloat()).toInt()
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
    private fun sourceToView(sy: Float): Float {
        if (vTranslate == null) return Float.NaN
        return sy * scale + vTranslate!!.y
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

}