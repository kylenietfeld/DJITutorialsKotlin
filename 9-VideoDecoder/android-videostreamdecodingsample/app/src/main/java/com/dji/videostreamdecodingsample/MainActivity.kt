package com.dji.videostreamdecodingsample

//import io.socket.client.Socket


import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
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
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.flightcontroller.virtualstick.*
import dji.common.product.Model
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import dji.common.util.CommonCallbacks
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.BufferedOutputStream
import android.graphics.Bitmap
import org.opencv.android.Utils
import android.widget.ImageView






class MainActivity : Activity(), DJICodecManager.YuvDataCallback {


    private var flightController: FlightController? = null
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
    private var prevtime = System.currentTimeMillis()
    private var curtime = System.currentTimeMillis()
    private var doneProcessing: Boolean = true
    private var points = Mat()
    private var rgbMat = Mat()
    private var yuvMat = Mat(1088 + 1088 / 2, 1632, CvType.CV_8UC1) //height = 1088, width = 1632
    private var ids = Mat()
    var product: BaseProduct? = null
    private var takeoff: Boolean = false
    private var pitch = 0f
    private var roll = 0f
    private var yaw = 0f
    private var throttle = 0f

    private lateinit var myImageView: ImageView


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
        //myImageView = findViewById(R.id.my_image_view)

        val aircraft: Aircraft? = DJISDKManager.getInstance().product as? Aircraft
        if (aircraft != null) {
            // Get the flight controller instance
            //flightController: FlightController? = aircraft.flightController
            flightController = aircraft.flightController
            if (flightController != null) {
                //showToast("Flight controller available!")
                flightController!!.verticalControlMode = VerticalControlMode.VELOCITY
                flightController!!.rollPitchControlMode = RollPitchControlMode.VELOCITY
                //flightController!!.yawControlMode = YawControlMode.ANGULAR_VELOCITY
                flightController!!.yawControlMode = YawControlMode.ANGLE
                flightController!!.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                flightController!!.setVirtualStickModeEnabled(true, null)

            } else {
                showToast("Flight controller not available")
            }
        } else {
            showToast("Aircraft not available")
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
            //Do things on sreen click
            takeoff()
        }
        updateUIVisibility()
    }
    private fun takeoff() {
        flightController?.startTakeoff(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(djiError: DJIError?) {
                if (djiError == null) {
                    showToast("Aircraft has taken off!")
                    takeoff=true
                } else {
                    showToast("Takeoff failed: $djiError")
                }
            }
        })

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

            //Get latest parameters from the server
            //yaw = receiveResponseFromServer() ?: 0.0f
            yaw = 0.0f

            //Increase count of iterations and set doneProcessing to false so the code waits until processing is done to start another
            count++
            doneProcessing = false


            //Fill bytes with YUV data
            val bytes = ByteArray(dataSize)
            yuvFrame[bytes]

            //Put the YUV data in an OpenCV mat
            yuvMat.put(0, 0, bytes)

            // Convert the YUV data to RGB data
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_I420)

            // Display the image using OpenCV's imshow function
            Imgproc.cvtColor(rgbMat, rgbMat, Imgproc.COLOR_RGB2BGR)

            val bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, bitmap)

            // Update the ImageView on the UI thread
            runOnUiThread {
                //myImageView.setImageBitmap(bitmap)
            }


            //sendFrameToServer(rgbMat)





            //Detect ArUco Marker
            //val dictionary = Dictionary()
            //val parameters = DetectorParameters()
/*
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
                //runOnUiThread { displayPath("No markers detected") }
            }
*/
            //Show toast of variables of interest
            curtime = System.currentTimeMillis()
            if (count % 39 == 0) {
                showToast("Time (ms): ".plus(curtime.minus(prevtime)).plus(" Yaw: ").plus(yaw).plus(" count: ").plus(count) )
                //runOnUiThread { displayPath("Time (ms): ".plus(curtime.minus(prevtime)).plus(" Yaw: ").plus(yaw).plus(" count: ").plus(count)) }


            }
            if (count % 100 == 0 && takeoff) {
                flightController!!.sendVirtualStickFlightControlData(FlightControlData(roll, pitch, yaw, throttle), null)
            }
            prevtime = System.currentTimeMillis()

            //Detection is done. Ready to start another.
            doneProcessing = true
        }
    }

    fun willgetbackthis() {
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

    private var socket: Socket? = null // For connection to Server

    private fun connectToServer() {
        Thread {
            val host = "24.116.144.235" // replace with your server IP address
            val port = 9999 // replace with your server port
            val timeout = 15000 // 15 seconds

            try {

                // Connect to server
               // socket = Socket(host, port)

                // Connect to server with timeout
                val address = InetSocketAddress(host, port)
                val tempSocket = Socket()
                tempSocket.connect(address, timeout)

                // Send message to server

                //val outputStream = OutputStreamWriter(tempSocket.getOutputStream())
                //outputStream.write("Hello, server!")
                //outputStream.flush()
                showToast("Connected to Server: $host : $port")

                // Assign the socket to the class property
                socket = tempSocket

            } catch (e: Exception) {
                // Handle connection error
                showToast("Connection failed: ${e.message}")
            }
        }.start()
    }
    private fun receiveResponseFromServer(): Float? { // "?" means the returned value may sometimes be "Null"
        // Make sure the socket is initialized and connected before attempting to receive data
        if (socket != null && socket!!.isConnected) {
            try {
                // Receive response from server
                val inputStream = socket!!.getInputStream()
                val buffer = ByteArray(1024)
                val length = inputStream.read(buffer)

                if (length != -1) {
                    val response = buffer.copyOf(length).decodeToString()
                    val yawStr = response.substringAfter(":").substringBefore(";").trim() // Extract the yaw angle string between ":" and ";"
                    return yawStr.toFloat() // Convert the yaw angle string to a float
                }

            } catch (e: Exception) {
                // Handle error
                showToast("Error receiving response from server: ${e.message}")
            }
        } else {
            showToast("Socket is not connected")
        }
        return null
    }

    fun getFlightController(): FlightController? {
        val aircraft = product as Aircraft?
        val flightController = aircraft?.flightController

        if (flightController == null) {
            showToast("Flight controller not available")
        } else {
            showToast("Flight controller is available!")
        }

        return flightController
    }

    private fun sendFrameToServer(mat: Mat) {
        if (socket != null && socket!!.isConnected) {
            val outputStream = socket?.getOutputStream()
            if (outputStream != null) {
                val buffer = MatOfByte()
                Imgcodecs.imencode(".jpg", mat, buffer)
                val bytes = buffer.toArray()
                val dataOutputStream = DataOutputStream(outputStream)
                val payloadSize = mat.total() * mat.elemSize()

                // Send the payload size first
                try {
                    dataOutputStream.writeLong(payloadSize)
                    showToast("Payload size sent: $payloadSize")
                } catch (e: Exception) {
                    showToast("Failed to write payload size to DataOutputStream")
                }

                // Send the image data
                try {
                    dataOutputStream.write(bytes)
                    dataOutputStream.flush()
                    showToast("Image data sent successfully")
                } catch (e: Exception) {
                    showToast("Failed to write image data to DataOutputStream")
                }
            }
        } else {
            showToast("Socket is not connected")
        }
    }

















}