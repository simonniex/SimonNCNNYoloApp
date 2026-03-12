package com.example.yolodemo

import android.content.res.AssetManager
import android.graphics.Bitmap

data class YoloObject(
    val x: Float, val y: Float, val w: Float, val h: Float,
    val label: Int, val prob: Float
)

object YoloNcnn {
    init { System.loadLibrary("yoloncnn") }
    external fun init(assetManager: AssetManager, useGpu: Boolean): Boolean
    external fun detect(bitmap: Bitmap): Array<YoloObject>
}