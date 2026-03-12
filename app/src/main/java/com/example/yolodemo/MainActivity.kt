package com.example.yolodemo

import android.Manifest
import android.graphics.*
import android.os.Bundle
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        YoloNcnn.init(assets, true)
        setContent { CameraScreen() }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA)
    LaunchedEffect(Unit) { permissionState.launchPermissionRequest() }

    var isPortrait by remember { mutableStateOf(false) }

    if (permissionState.status.isGranted) {
        Box(modifier = Modifier.fillMaxSize()) {
            key(isPortrait) {
                YoloCameraAnalyzer(isPortrait)
            }

            Button(
                onClick = { isPortrait = !isPortrait },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            ) {
                Text(text = if (isPortrait) "当前: 竖屏模式" else "当前: 横屏模式")
            }
        }
    }
}

@Composable
fun YoloCameraAnalyzer(isPortrait: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // 推理结果状态
    var objects by remember { mutableStateOf<List<YoloObject>>(emptyList()) }
    // 图像元数据
    var frameWidth by remember { mutableIntStateOf(1) }
    var frameHeight by remember { mutableIntStateOf(1) }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(executor) {
        onDispose { executor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            //工厂方法：用于创建并配置 Android 原生的 PreviewView
            factory = { ctx ->
                //创建 PreviewView 实例（CameraX 提供的预览视图）
                val previewView = PreviewView(ctx).apply {
                    // 设置预览缩放类型为居中适配
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                //获取 CameraProvider 实例（CameraX 的核心管理类，用于绑定相机生命周期）
                val providerFuture = ProcessCameraProvider.getInstance(ctx)

                //为 CameraProvider 的初始化添加监听（异步回调）
                providerFuture.addListener({
                    // 获取初始化完成的 CameraProvider
                    val provider = providerFuture.get()

                    // 根据屏幕方向设置相机预览的目标分辨率
                    // 竖屏：720x1280  横屏：1280x720
                    val targetResolution = if (isPortrait) Size(720, 1280) else Size(1280, 720)

                    // 根据屏幕方向设置相机预览的目标旋转角度
                    // 竖屏：0度  横屏：90度
                    val targetRotation = if (isPortrait) Surface.ROTATION_0 else Surface.ROTATION_90

                    //配置相机预览用例
                    val preview = Preview.Builder()
                        //设置预览分辨率
                        .setTargetResolution(targetResolution)
                        //设置预览旋转角度
                        .setTargetRotation(targetRotation)
                        .build()
                        // 将预览输出绑定到 PreviewView 的 SurfaceProvider
                        .also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                    // 5. 配置图像分析用例（用于实时处理相机帧数据）
                    val analysis = ImageAnalysis.Builder()
                        // 设置分析图像的分辨率
                        .setTargetResolution(targetResolution)
                        // 设置分析图像的旋转角度
                        .setTargetRotation(targetRotation)
                        // 设置背压策略：只保留最新的帧（丢弃旧帧，避免卡顿）
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // 设置输出图像格式：RGBA_8888（兼容 Bitmap 处理）
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    // 设置图像分析器（处理每一帧相机数据）
                    analysis.setAnalyzer(executor) { proxy ->
                        // 将 ImageProxy 转换为 Bitmap（便于后续处理）
                        var bitmap = proxy.toBitmap()

                        // 判断 Bitmap 的方向（是否为竖屏：高度 >= 宽度）
                        val isBitmapPortrait = bitmap.height >= bitmap.width

                        // 如果 Bitmap 方向与屏幕方向不一致，旋转矫正
                        if (isPortrait != isBitmapPortrait) {
                            val matrix = Matrix().apply {
                                // 旋转 90 度矫正方向
                                postRotate(90f)
                            }
                            // 创建旋转后的新 Bitmap
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        }

                        frameWidth = bitmap.width
                        frameHeight = bitmap.height

                        val results = YoloNcnn.detect(bitmap)
                        objects = results.toList()
                        proxy.close()
                    }
                    // 绑定相机用例到生命周期
                    // 先解绑所有已绑定的用例（避免内存泄漏）
                    provider.unbindAll()
                    // 绑定预览、分析用例到相机生命周期
                    // 参数：生命周期所有者、默认后置摄像头、预览用例、图像分析用例
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                // 返回创建好的 PreviewView 作为 AndroidView 的内容
                previewView
            }
        )

        // 绘图层
        Canvas(modifier = Modifier.fillMaxSize()) {
            val screenW = size.width
            val screenH = size.height

            // 根据当前模式确定映射到屏幕上的真实画面宽高
            val previewW = if (isPortrait) frameWidth else frameHeight
            val previewH = if (isPortrait) frameHeight else frameWidth

            // 计算缩放，确保画布能铺满/居中
            val scale = minOf(screenW / previewW.toFloat(), screenH / previewH.toFloat())
            val offsetX = (screenW - previewW * scale) / 2f
            val offsetY = (screenH - previewH * scale) / 2f

            val labels = arrayOf("人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船", "红绿灯", "消防栓", "停止标志", "停车表", "长椅", "鸟", "猫", "狗", "马", "羊", "牛", "象", "熊", "斑马", "长颈鹿", "背包", "雨伞", "手提包", "领带", "手提箱", "飞盘", "滑雪板", "单板滑雪", "运动球", "风成品", "棒球棒", "棒球手套", "滑板", "冲浪板", "网球拍", "瓶子", "红酒杯", "杯子", "叉子", "刀", "勺子", "碗", "香蕉", "苹果", "三明治", "橙子", "西兰花", "胡萝卜", "热狗", "比萨饼", "甜甜圈", "蛋糕", "椅子", "沙发", "盆栽", "床", "餐桌", "马桶", "电视", "笔记本电脑", "鼠标", "遥控器", "键盘", "手机", "微波炉", "烤箱", "烤面包机", "洗手池", "冰箱", "书", "时钟", "花瓶", "剪刀", "泰迪熊", "吹风机", "牙刷")

            val paint = Paint().apply {
                color = android.graphics.Color.YELLOW
                textSize = 32f
                style = Paint.Style.FILL
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }

            objects.forEach { obj ->
                val l: Float
                val t: Float
                val r: Float
                val b: Float

                if (isPortrait) {
                    // 竖屏状态
                    l = obj.x * scale + offsetX
                    t = obj.y * scale + offsetY
                    r = (obj.x + obj.w) * scale + offsetX
                    b = (obj.y + obj.h) * scale + offsetY
                } else {
                    // 横屏状态：顺时针旋转 90 度映射 (CW90)
                    val rotatedX = frameHeight - obj.y - obj.h
                    val rotatedY = obj.x
                    val rotatedW = obj.h
                    val rotatedH = obj.w

                    // 再应用缩放和位移
                    l = rotatedX * scale + offsetX
                    t = rotatedY * scale + offsetY
                    r = (rotatedX + rotatedW) * scale + offsetX
                    b = (rotatedY + rotatedH) * scale + offsetY
                }

                //使用 coerceIn 限制在屏幕内
                val rectL = l.coerceIn(0f, screenW)
                val rectT = t.coerceIn(0f, screenH)
                val rectR = r.coerceIn(0f, screenW)
                val rectB = b.coerceIn(0f, screenH)

                // 只有有效的矩形才绘制
                if (rectR > rectL && rectB > rectT) {
                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(rectL, rectT),
                        size = androidx.compose.ui.geometry.Size(rectR - rectL, rectB - rectT),
                        style = Stroke(width = 3f)
                    )

                    // 文字，增加越界处理
                    val text = "${labels.getOrElse(obj.label) { "未知" }} ${(obj.prob * 100).toInt()}%"

                    //绘制旋转文字
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    //备份 Canvas 状态
                    nativeCanvas.save()

                    //将 Canvas 原点平移到文字绘制位置
                    // 横屏模式下，需要拧正 Canvas，所以原点平移到框的左上角 (rectL, rectT)
                    val textX = rectL + 10f
                    val baseTextY = if (rectT > 60f) rectT - 10f else rectT + 40f
                    nativeCanvas.translate(textX, baseTextY)

                    if (!isPortrait) {
                        //如果不是竖屏，顺时针旋转 90 度 Canvas
                        nativeCanvas.rotate(90f)
                    }

                    //绘制文字（此时 Canvas 已旋转，相对于内容是正的）
                    //此时在旋转后的本地坐标系中，沿本地 X 轴水平绘制，基准点为新的原点 (0, 0)
                    nativeCanvas.drawText(text, 0f, 0f, paint)

                    //恢复 Canvas 状态
                    nativeCanvas.restore()
                }
            }
        }
    }
}