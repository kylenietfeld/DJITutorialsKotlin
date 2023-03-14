package com.dji.videostreamdecodingsample

import android.app.Activity
import android.graphics.*
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.*
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder
import com.dji.videostreamdecodingsample.media.NativeHelper
import dji.common.airlink.PhysicalSource
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.product.Model
import dji.sdk.airlink.OcuSyncLink
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.sdkmanager.DJISDKManager
import dji.thirdparty.afinal.core.AsyncTask
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Dictionary
import org.opencv.calib3d.Calib3d
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeDetector
import java.io.*
import java.nio.ByteBuffer
import io.socket.client.IO
//import io.socket.client.Socket
import java.net.Socket
import java.io.BufferedReader
import org.apache.commons.net.telnet.TelnetClient
import java.io.InputStreamReader

class MainActivity : Activity(), DJICodecManager.YuvDataCallback {



    private var surfaceCallback: SurfaceHolder.Callback? = null

    private enum class DemoType {
        USE_TEXTURE_VIEW, USE_SURFACE_VIEW, USE_SURFACE_VIEW_DEMO_DECODER
    }

    private var standardVideoFeeder: VideoFeeder.VideoFeed? = null
    private var mReceivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private var titleTv: TextView? = null
    private var mainHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_WHAT_SHOW_TOAST -> Toast.makeText(
                    applicationContext, msg.obj as String, Toast.LENGTH_SHORT
                ).show()
                MSG_WHAT_UPDATE_TITLE -> if (titleTv != null) {
                    titleTv!!.text = msg.obj as String
                }
                else -> {}
            }
        }
    }
    private var videostreamPreviewTtView: TextureView? = null
    private var videostreamPreviewSf: SurfaceView? = null
    private var videostreamPreviewSh: SurfaceHolder? = null
    private var mCamera: Camera? = null
    private var mCodecManager: DJICodecManager? = null
    private var savePath: TextView? = null
    private var screenShot: Button? = null
    private var stringBuilder: StringBuilder? = null
    private var videoViewWidth = 0
    private var videoViewHeight = 0
    private var count = 0
    private var countmax = 30
    private var prevtime = System.currentTimeMillis()
    private var curtime = System.currentTimeMillis()

    private var doneProcessing: Boolean = true
    private val decoder = QRCodeDetector()
    private var points = Mat()
    private var rgbMat = Mat()



    private var ids = Mat()
    private var yuvMat = Mat(1088 + 1088 / 2, 1632, CvType.CV_8UC1) //height = 1088, width = 1632

    //private var socket: Socket? = null





    override fun onResume() {
        super.onResume()
        initSurfaceOrTextureView()
        notifyStatusChange()
    }

    private fun initSurfaceOrTextureView() {
        when (demoType) {
            DemoType.USE_SURFACE_VIEW -> initPreviewerSurfaceView()
            DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                /**
                 * we also need init the textureView because the pre-transcoded video steam will display in the textureView
                 */
                initPreviewerTextureView()
                /**
                 * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                 * on surfaceView
                 */
                initPreviewerSurfaceView()
            }
            DemoType.USE_TEXTURE_VIEW -> initPreviewerTextureView()
            else -> {}
        }
    }

    override fun onPause() {
        if (mCamera != null) {
            VideoFeeder.getInstance().primaryVideoFeed
                .removeVideoDataListener(mReceivedVideoDataListener)
            standardVideoFeeder?.removeVideoDataListener(mReceivedVideoDataListener)
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (mCodecManager != null) {
            mCodecManager!!.cleanSurface()
            mCodecManager!!.destroyCodec()
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initUi()
        connectToServer()


        if (MainActivity.isM300Product) {
            val ocuSyncLink: OcuSyncLink? =
                VideoDecodingApplication.productInstance?.airLink?.ocuSyncLink
            // If your MutltipleLensCamera is set at right or top, you need to change the PhysicalSource to RIGHT_CAM or TOP_CAM.
            if (ocuSyncLink != null) {
                ocuSyncLink.assignSourceToPrimaryChannel(
                    PhysicalSource.LEFT_CAM, PhysicalSource.FPV_CAM
                ) { error: DJIError? ->
                    if (error == null) {
                        showToast("assignSourceToPrimaryChannel success.")
                    } else {
                        showToast("assignSourceToPrimaryChannel fail, reason: " + error.description)
                    }
                }
            }
        }
    }



    private fun showToast(s: String) {
        mainHandler.sendMessage(
            mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        )
    }

    private fun updateTitle(s: String) {
        mainHandler.sendMessage(
            mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        )
    }

    private fun initUi() {
        savePath = findViewById<View>(R.id.activity_main_save_path) as TextView
        screenShot = findViewById<View>(R.id.activity_main_screen_shot) as Button
        screenShot!!.isSelected = false
        titleTv = findViewById<View>(R.id.title_tv) as TextView
        videostreamPreviewTtView = findViewById<View>(R.id.livestream_preview_ttv) as TextureView
        videostreamPreviewSf = findViewById<View>(R.id.livestream_preview_sf) as SurfaceView
        videostreamPreviewSf!!.isClickable = true
        videostreamPreviewSf!!.setOnClickListener {
            if (countmax == 30) {
                countmax = 1
                showToast("Countmax set rate to 1")
            } else if (countmax == 1){
                countmax = 10
                showToast("Countmax set rate to 10")
        } else if (countmax == 10){
            countmax = 20
            showToast("Countmax set rate to 20")
        }
            else{
                countmax = 30;
                showToast("Countmax set rate to 30")
            }
            /*val rate: Float = VideoFeeder.getInstance().transcodingDataRate
            showToast("current rate:" + rate + "Mbps")
            if (rate < 10) {
                VideoFeeder.getInstance().transcodingDataRate = 10.0f
                showToast("set rate to 10Mbps")
            } else {
                VideoFeeder.getInstance().transcodingDataRate = 3.0f
                showToast("set rate to 3Mbps")
            }*/
        }
        updateUIVisibility()
    }

    private fun updateUIVisibility() {
        when (demoType) {
            DemoType.USE_SURFACE_VIEW -> {
                videostreamPreviewSf!!.visibility = View.VISIBLE
                videostreamPreviewTtView!!.visibility = View.GONE
            }
            DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                /**
                 * we need display two video stream at the same time, so we need let them to be visible.
                 */
                videostreamPreviewSf!!.visibility = View.VISIBLE
                videostreamPreviewTtView!!.visibility = View.VISIBLE
            }
            DemoType.USE_TEXTURE_VIEW -> {
                videostreamPreviewSf!!.visibility = View.GONE
                videostreamPreviewTtView!!.visibility = View.VISIBLE
            }
            else -> {}
        }
    }

    private var lastupdate: Long = 0
    private fun notifyStatusChange() {
        val product: BaseProduct? = VideoDecodingApplication.productInstance
        Log.d(
            TAG,
            "notifyStatusChange: " + when {
                product == null -> "Disconnect"
                product.model == null -> "null model"
                else -> product.model.name
            }
        )
        if (product != null) {
            if (product.isConnected && product.model != null) {
                updateTitle(product.model.name + " Connected " + demoType?.name)
            } else {
                updateTitle("Disconnected")
            }
        }

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener =
            VideoFeeder.VideoDataListener { videoBuffer, size ->
                if (System.currentTimeMillis() - lastupdate > 1000) {
                    Log.d(
                        TAG,
                        "camera recv video data size: $size"
                    )
                    lastupdate = System.currentTimeMillis()
                }
               // val image = mCodecManager?.getBitmap(applicationContext)


                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> mCodecManager?.sendDataToDecoder(videoBuffer, size)
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER ->
                        /**
                         * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                         * on surfaceView
                         */
                        /**
                         * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                         * on surfaceView
                         */
                        DJIVideoStreamDecoder.instance?.parse(videoBuffer, size)
                    DemoType.USE_TEXTURE_VIEW -> mCodecManager?.sendDataToDecoder(videoBuffer, size)
                    else -> {}
                }
            }
        if (product != null) {
            if (!product.isConnected) {
                mCamera = null
                showToast("Disconnected")
            } else {
                if (!product.model.equals(Model.UNKNOWN_AIRCRAFT)) {
                    mCamera = product.camera
                    if (mCamera != null) {
                        if (mCamera!!.isFlatCameraModeSupported) {
                            mCamera!!.setFlatMode(
                                SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE
                            ) { djiError: DJIError? ->
                                if (djiError != null) {
                                    showToast("can't change flat mode of camera, error:" + djiError.description)
                                }
                            }
                        } else {
                            mCamera!!.setMode(
                                SettingsDefinitions.CameraMode.SHOOT_PHOTO
                            ) { djiError: DJIError? ->
                                if (djiError != null) {
                                    showToast("can't change mode of camera, error:" + djiError.description)
                                }
                            }
                        }
                    }

                    //When calibration is needed or the fetch key frame is required by SDK, should use the provideTranscodedVideoFeed
                    //to receive the transcoded video feed from main camera.
                    if (demoType == DemoType.USE_SURFACE_VIEW_DEMO_DECODER && isTranscodedVideoFeedNeeded) {
                        standardVideoFeeder = VideoFeeder.getInstance().provideTranscodedVideoFeed()
                        standardVideoFeeder!!.addVideoDataListener(mReceivedVideoDataListener!!)
                        return
                    }
                    VideoFeeder.getInstance().primaryVideoFeed
                        .addVideoDataListener(mReceivedVideoDataListener!!)
                }
            }
        }
    }

    /**
     * Init a fake texture view to for the codec manager, so that the video raw data can be received
     * by the camera
     */
    private fun initPreviewerTextureView() {
        videostreamPreviewTtView!!.surfaceTextureListener = object :
            TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "real onSurfaceTextureAvailable")
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable: width $videoViewWidth height $videoViewHeight"
                )
                if (mCodecManager == null) {
                    mCodecManager = DJICodecManager(applicationContext, surface, width, height)
                    //For M300RTK, you need to actively request an I frame.
                    mCodecManager!!.resetKeyFrame()
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable2: width $videoViewWidth height $videoViewHeight"
                )
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                mCodecManager?.cleanSurface()
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    /**
     * Init a surface view for the DJIVideoStreamDecoder
     */
    private fun initPreviewerSurfaceView() {
        videostreamPreviewSh = videostreamPreviewSf!!.holder
        surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "real onSurfaceTextureAvailable")
                videoViewWidth = videostreamPreviewSf!!.width
                videoViewHeight = videostreamPreviewSf!!.height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable3: width $videoViewWidth height $videoViewHeight"
                )
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> if (mCodecManager == null) {
                        mCodecManager = DJICodecManager(
                            applicationContext, holder, videoViewWidth,
                            videoViewHeight
                        )
                    }
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                        // This demo might not work well on P3C and OSMO.
                        NativeHelper.instance?.init()
                        DJIVideoStreamDecoder.instance?.init(applicationContext, holder.surface)
                        DJIVideoStreamDecoder.instance?.resume()
                    }
                    else -> {}
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable4: width $videoViewWidth height $videoViewHeight"
                )
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> {}
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> DJIVideoStreamDecoder.instance
                        ?.changeSurface(holder.surface)
                    else -> {}
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> if (mCodecManager != null) {
                        mCodecManager!!.cleanSurface()
                        mCodecManager!!.destroyCodec()
                        mCodecManager = null
                    }
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                        DJIVideoStreamDecoder.instance?.stop()
                        NativeHelper.instance?.release()
                    }
                    else -> {}
                }
            }
        }
        videostreamPreviewSh!!.addCallback(surfaceCallback)
    }

    override fun onYuvDataReceived(format: MediaFormat, yuvFrame: ByteBuffer?, dataSize: Int, width: Int, height: Int) {

        if ( doneProcessing && yuvFrame != null) {

            //Increase count of iterations and set doneProcessing to false so the code waits until processing is done to start another
            count++
            doneProcessing = false

            //Fill bytes with YUV data
            val bytes = ByteArray(dataSize)
            yuvFrame[bytes]

            //Put the YUV data in an OpenCV mat
            yuvMat.put(0, 0, bytes)

            //Convert the YUV data to RGB data
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_I420)

            /*
            //Detect QR Code and show result
            var data = decoder.detectAndDecode(rgbMat, points)
            if (count % 10 == 0) {
                if (points.empty()) {
                    runOnUiThread { displayPath("No QR Code Found ") }
                } else {
                    runOnUiThread { displayPath(data) }
                }
            }*/

            //Detect ArUco Marker
            //val dictionary = Dictionary()
            //val parameters = DetectorParameters()

            val corners  = mutableListOf<Mat>()

            val detector = ArucoDetector()
            detector.detectMarkers(rgbMat, corners , ids)

            //if (ids.total() > 0)  {
               // runOnUiThread { displayPath("Detected marker") }
           // }

            //Aruco marker found
            if (ids.total() > 0 && count % 5 == 0)  {
                for (i in 0 until ids.total().toInt()) {
                    showToast("Detected marker with id: ${ids[i, 0][0]}")

                }
            } else {
                runOnUiThread { displayPath("No markers detected") }
            }

            //Calculate frames/second and show toast
            curtime = System.currentTimeMillis()
            if (count % 10 == 0) {
                //showToast("Time (ms): ".plus(curtime.minus(prevtime)).toString().plus(", Height: ").plus(height).plus(", Width: ").plus(width))
                receiveResponseFromServer()
                /*val cameraMatrix = Mat(3, 3, CvType.CV_64F)
                val distCoeffs = MatOfDouble()
                distCoeffs.put(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
                val markerSize = 0.1 // For example, if the marker size is 10 cm
                val markerPixelCoords = Point(x, y)
                // Define the half of the camera's field of view in the vertical direction
                val fov = 40.0 // In degrees
                val rvec = Mat()
                val tvec = Mat()
                val markerSizeMeters = 0.1 // assume the marker size is 10cm
                val objPoints = MatOfPoint3f(
                    Point3(0.0, 0.0, 0.0),
                    Point3(0.0, markerSizeMeters, 0.0),
                    Point3(markerSizeMeters, markerSizeMeters, 0.0),
                    Point3(markerSizeMeters, 0.0, 0.0)
                )


                Calib3d.solvePnP(
                    objPoints = objPoints,
                    imgPoints = MatOfPoint2f(markerPixelCoords),
                    cameraMatrix = cameraMatrix,
                    distCoeffs = distCoeffs,
                    rvec = rvec,
                    tvec = tvec,
                    useExtrinsicGuess = false
                )*/
            }
            prevtime = System.currentTimeMillis()

            //Detection is done. Ready to start another.
            doneProcessing = true

/*
            AsyncTask.execute(Runnable {
                    // two samples here, it may have other color format.
                    when (format.getInteger(MediaFormat.KEY_COLOR_FORMAT)) {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ->                             //NV12
                            if (Build.VERSION.SDK_INT <= 23) {
                                //oldSaveYuvDataToJPEG(bytes, width, height)

                            } else {
                                //newSaveYuvDataToJPEG(bytes, width, height)

                            }
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ->                             //YUV420P
                            newSaveYuvDataToJPEG420P(bytes, width, height)
                        else -> {}

                    }
                })*/
        }
    }

    // For android API <= 23
/*
    private fun oldSaveYuvDataToJPEG(yuvFrame: ByteArray, width: Int, height: Int) {
        showToast("oldSaveYuvDataToJPEG")
        if (yuvFrame.size < width * height) {
            //DJILog.d(TAG, "yuvFrame size is too small " + yuvFrame.length);
            return
        }
        val y = ByteArray(width * height)
        val u = ByteArray(width * height / 4)
        val v = ByteArray(width * height / 4)
        val nu = ByteArray(width * height / 4) //
        val nv = ByteArray(width * height / 4)
        System.arraycopy(yuvFrame, 0, y, 0, y.size)
        for (i in u.indices) {
            v[i] = yuvFrame[y.size + 2 * i]
            u[i] = yuvFrame[y.size + 2 * i + 1]
        }
        val uvWidth = width / 2
        val uvHeight = height / 2
        for (j in 0 until uvWidth / 2) {
            for (i in 0 until uvHeight / 2) {
                val uSample1 = u[i * uvWidth + j]
                val uSample2 = u[i * uvWidth + j + uvWidth / 2]
                val vSample1 = v[(i + uvHeight / 2) * uvWidth + j]
                val vSample2 = v[(i + uvHeight / 2) * uvWidth + j + uvWidth / 2]
                nu[2 * (i * uvWidth + j)] = uSample1
                nu[2 * (i * uvWidth + j) + 1] = uSample1
                nu[2 * (i * uvWidth + j) + uvWidth] = uSample2
                nu[2 * (i * uvWidth + j) + 1 + uvWidth] = uSample2
                nv[2 * (i * uvWidth + j)] = vSample1
                nv[2 * (i * uvWidth + j) + 1] = vSample1
                nv[2 * (i * uvWidth + j) + uvWidth] = vSample2
                nv[2 * (i * uvWidth + j) + 1 + uvWidth] = vSample2
            }
        }
        //nv21test
        val bytes = ByteArray(yuvFrame.size)
        System.arraycopy(y, 0, bytes, 0, y.size)
        for (i in u.indices) {
            bytes[y.size + i * 2] = nv[i]
            bytes[y.size + i * 2 + 1] = nu[i]
        }
        Log.d(
            TAG,
            ("onYuvDataReceived: frame index: "
                    + DJIVideoStreamDecoder.instance?.frameIndex
                    ) + ",array length: "
                    + bytes.size
        )
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            screenShot(
                bytes,
                applicationContext.getExternalFilesDir("DJI")!!.path + "/DJI_ScreenShot",
                width,
                height
            )
        } else {
            screenShot(
                bytes,
                Environment.getExternalStorageDirectory().toString() + "/DJI_ScreenShot",
                width,
                height
            )
        }
    }


    private fun newSaveYuvDataToJPEG(yuvFrame: ByteArray, width: Int, height: Int) {
        showToast("newSaveYuvDataToJPEG")
        if (yuvFrame.size < width * height) {
            //DJILog.d(TAG, "yuvFrame size is too small " + yuvFrame.length);
            return
        }
        val length = width * height
        val u = ByteArray(width * height / 4)
        val v = ByteArray(width * height / 4)
        for (i in u.indices) {
            v[i] = yuvFrame[length + 2 * i]
            u[i] = yuvFrame[length + 2 * i + 1]
        }
        for (i in u.indices) {
            yuvFrame[length + 2 * i] = u[i]
            yuvFrame[length + 2 * i + 1] = v[i]
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            screenShot(yuvFrame, applicationContext.getExternalFilesDir("DJI")!!.path + "/DJI_ScreenShot", width, height)
        } else {
            screenShot(yuvFrame, Environment.getExternalStorageDirectory().toString() + "/DJI_ScreenShot", width, height)
        }
    }
*/

    private fun newSaveYuvDataToJPEG420P(yuvFrame: ByteArray, width: Int, height: Int) {
        //showToast("newSaveYuvDataToJPEG420P")
        if (yuvFrame.size < width * height) {
            return
        }
        val length = width * height
        val u = ByteArray(width * height / 4)
        val v = ByteArray(width * height / 4)
        for (i in u.indices) {
            u[i] = yuvFrame[length + i]
            v[i] = yuvFrame[length + u.size + i]
        }
        for (i in u.indices) {
            yuvFrame[length + 2 * i] = v[i]
            yuvFrame[length + 2 * i + 1] = u[i]
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            screenShot(yuvFrame, applicationContext.getExternalFilesDir("DJI")!!.path + "/DJI_ScreenShot", width, height)
        } else {
            screenShot(yuvFrame, Environment.getExternalStorageDirectory().toString() + "/DJI_ScreenShot", width, height)
        }
    }

    /**
     * Save the buffered data into a JPG image file
     */


    private fun screenShot(buf: ByteArray, shotDir: String, width: Int, height: Int) {
        val dir = File(shotDir)
        if (!dir.exists() || !dir.isDirectory) {
            dir.mkdirs()
        }

        val yuvImage = YuvImage(buf, ImageFormat.NV21, width, height, null)

        val outputFile: OutputStream
        val path = dir.toString() + "/ScreenShot_" + System.currentTimeMillis() + ".jpg"
        outputFile = try {
            FileOutputStream(File(path))
        } catch (e: FileNotFoundException) {
            Log.e(
                TAG,
                "test screenShot: new bitmap output file error: $e"
            )
            return
        }
        yuvImage.compressToJpeg(
            Rect(
                0,
                0,
                width,
                height
            ), 100, outputFile
        )
        try {
            outputFile.close()
        } catch (e: IOException) {
            Log.e(
                TAG,
                "test screenShot: compress yuv image error: $e"
            )
            e.printStackTrace()
        }

        //This section added by Kyle
        val img = Imgcodecs.imread(path)


        val data = decoder.detectAndDecode(img, points)

        //If QR code is scanned, print out the contents.
        if (points.empty()) {
            runOnUiThread { displayPath("No QR Code Found ") }
        }else{
            runOnUiThread { displayPath(data) }
        }

        //runOnUiThread { displayPath(path) }

        //Calculate frames/second and show
        curtime = System.currentTimeMillis()
       // time = curtime.minus(prevtime)
        //if(count%10==0) {runOnUiThread { displayPath(time.toString()) }}
        if(count++ % 5==0) {showToast(curtime.minus(prevtime).toString().plus(" ").plus(count.toString()).plus(" ").plus(countmax.toString())          )    }
        prevtime = System.currentTimeMillis()

        doneProcessing = true //ready to process the next image

    }

    fun onClick(v: View) {
        if (v.id == R.id.activity_main_screen_shot) {
            handleYUVClick()
        } else {
            var newDemoType: DemoType? = null
            if (v.id == R.id.activity_main_screen_texture) {
                newDemoType = DemoType.USE_TEXTURE_VIEW
            } else if (v.id == R.id.activity_main_screen_surface) {
                newDemoType = DemoType.USE_SURFACE_VIEW
            } else if (v.id == R.id.activity_main_screen_surface_with_own_decoder) {
                newDemoType = DemoType.USE_SURFACE_VIEW_DEMO_DECODER
            }
            if (newDemoType != null && newDemoType != demoType) {
                // Although finish will trigger onDestroy() is called, but it is not called before OnCreate of new activity.
                if (mCodecManager != null) {
                    mCodecManager!!.cleanSurface()
                    mCodecManager!!.destroyCodec()
                    mCodecManager = null
                }
                demoType = newDemoType
                finish()
                overridePendingTransition(0, 0)
                startActivity(intent)
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun handleYUVClick() {
        if (screenShot!!.isSelected) {
            screenShot!!.text = "YUV Screen Shot"
            screenShot!!.isSelected = false
            when (demoType) {
                DemoType.USE_SURFACE_VIEW, DemoType.USE_TEXTURE_VIEW -> {
                    mCodecManager?.enabledYuvData(false)
                    mCodecManager?.yuvDataCallback = null
                }
                DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                    DJIVideoStreamDecoder.instance
                        ?.changeSurface(videostreamPreviewSh!!.surface)
                    DJIVideoStreamDecoder.instance?.setYuvDataListener(null)
                }
                else -> {}
            }
            savePath!!.text = ""
            savePath!!.visibility = View.INVISIBLE
            stringBuilder = null
        } else {
            screenShot!!.text = "Live Stream"
            screenShot!!.isSelected = true
            when (demoType) {
                DemoType.USE_TEXTURE_VIEW, DemoType.USE_SURFACE_VIEW -> {
                    mCodecManager?.enabledYuvData(true)
                    mCodecManager?.yuvDataCallback = this
                }
                DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                    DJIVideoStreamDecoder.instance?.changeSurface(null)
                    DJIVideoStreamDecoder.instance?.setYuvDataListener(this@MainActivity)
                }
                else -> {}
            }
            savePath!!.text = ""
            savePath!!.visibility = View.VISIBLE
        }
    }

    private fun displayPath(_path: String) {
        var path = _path
        if (stringBuilder == null) {
            stringBuilder = StringBuilder()
        }
        path = """
            $path
            
            """.trimIndent()
        stringBuilder!!.append(path)
        savePath!!.text = stringBuilder.toString()
    }

    private val isTranscodedVideoFeedNeeded: Boolean
        get() = if (VideoFeeder.getInstance() == null) {
            false
        } else VideoFeeder.getInstance().isFetchKeyFrameNeeded || VideoFeeder.getInstance()
            .isLensDistortionCalibrationNeeded

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val MSG_WHAT_SHOW_TOAST = 0
        private const val MSG_WHAT_UPDATE_TITLE = 1
        private var demoType: DemoType? = DemoType.USE_TEXTURE_VIEW
        val isM300Product: Boolean
            get() {
                if (DJISDKManager.getInstance().product == null) {
                    return false
                }
                val model: Model = DJISDKManager.getInstance().product.model
                return model === Model.MATRICE_300_RTK
            }
    }

    private var socket: Socket? = null

    private fun connectToServer() {
        Thread {
            val host = "184.155.86.105" // replace with your server IP address
            val port = 23 // replace with your server port
            //val terminalType = "VT100" // replace with your desired terminal type

            // Connect to server
            try {
                socket = Socket(host, port)

                // Send message to server
                val outputStream = OutputStreamWriter(socket!!.getOutputStream())
                outputStream.write("Hello, server!")
                outputStream.flush()

            } catch (e: Exception) {
                // Handle connection error
                showToast("Connection failed: ${e.message}")
            }
        }.start()
    }

    private fun receiveResponseFromServer() {
        // Make sure the socket is initialized and connected before attempting to receive data
        if (socket != null && socket!!.isConnected) {
            try {
                // Receive response from server
                val inputStream = socket!!.getInputStream()
                val buffer = ByteArray(1024)
                val length = inputStream.read(buffer)

                if (length != -1) {
                    val response = buffer.copyOf(length).decodeToString()
                    showToast("Received response from server: $response")
                }
            } catch (e: Exception) {
                // Handle error
                showToast("Error receiving response from server: ${e.message}")
            }
        } else {
            showToast("Socket is not connected")
        }
    }




}