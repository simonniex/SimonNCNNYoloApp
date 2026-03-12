#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include "net.h"

#define LOG_TAG "YoloNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static ncnn::Net yolo;

struct Object {
    float x, y, w, h;
    int label;
    float prob;
};

static inline float intersection_area(const Object& a, const Object& b) {
    float inter_width = std::min(a.x + a.w, b.x + b.w) - std::max(a.x, b.x);
    float inter_height = std::min(a.y + a.h, b.y + b.h) - std::max(a.y, b.y);
    if (inter_width <= 0 || inter_height <= 0) return 0.f;
    return inter_width * inter_height;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_yolodemo_YoloNcnn_init(JNIEnv *env, jobject thiz, jobject assetManager, jboolean use_gpu) {
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4; // 增加线程数提高灵敏度
    opt.use_vulkan_compute = use_gpu;
    opt.use_fp16_arithmetic = false; // 某些机型 FP16 会导致检测不到
    yolo.opt = opt;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    int ret1 = yolo.load_param(mgr, "yolov8n.param");
    int ret2 = yolo.load_model(mgr, "yolov8n.bin");
    return (ret1 == 0 && ret2 == 0);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_yolodemo_YoloNcnn_detect(JNIEnv *env, jobject thiz, jobject bitmap) {
    const int target_size = 640;
    const float prob_threshold = 0.25f; // 降低阈值提高灵敏度
    const float nms_threshold = 0.45f;

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    const int width = info.width;
    const int height = info.height;

    // 1. 等比缩放计算
    float scale = 1.f;
    int w = width;
    int h = height;
    if (w > h) {
        scale = (float)target_size / w;
        w = target_size;
        h = h * scale;
    } else {
        scale = (float)target_size / h;
        h = target_size;
        w = w * scale;
    }

    ncnn::Mat in = ncnn::Mat::from_android_bitmap_resize(env, bitmap, ncnn::Mat::PIXEL_RGBA2RGB, w, h);

    // 2. Letterbox 填充 (Padding)
    int wpad = target_size - w;
    int hpad = target_size - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 114.f);

    const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in_pad.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = yolo.create_extractor();
    ex.input("in0", in_pad);
    ncnn::Mat out;
    ex.extract("out0", out);

    std::vector<Object> proposals;
    if (!out.empty()) {
        int num_anchors = out.w;
        int num_classes = out.h - 4;
        bool is_transposed = (out.w == 84 && out.h == 8400);

        if (is_transposed) {
            num_anchors = out.h;
            num_classes = out.w - 4;
        }

        for (int i = 0; i < num_anchors; i++) {
            float max_prob = 0.f;
            int label = -1;
            for (int c = 0; c < num_classes; c++) {
                float prob = is_transposed ? out.row(i)[c + 4] : out.row(c + 4)[i];
                if (prob > max_prob) {
                    max_prob = prob;
                    label = c;
                }
            }

            if (max_prob > prob_threshold) {
                float cx = is_transposed ? out.row(i)[0] : out.row(0)[i];
                float cy = is_transposed ? out.row(i)[1] : out.row(1)[i];
                float bw = is_transposed ? out.row(i)[2] : out.row(2)[i];
                float bh = is_transposed ? out.row(i)[3] : out.row(3)[i];

                // 还原坐标：减去 Padding，除以 Scale
                Object obj;
                obj.x = (cx - wpad / 2.f - bw / 2.f) / scale;
                obj.y = (cy - hpad / 2.f - bh / 2.f) / scale;
                obj.w = bw / scale;
                obj.h = bh / scale;
                obj.label = label;
                obj.prob = max_prob;

                // 严格边界限制
                obj.x = std::max(0.f, std::min((float)width - 1.f, obj.x));
                obj.y = std::max(0.f, std::min((float)height - 1.f, obj.y));
                obj.w = std::max(0.f, std::min((float)width - obj.x, obj.w));
                obj.h = std::max(0.f, std::min((float)height - obj.y, obj.h));

                proposals.push_back(obj);
            }
        }
    }

    // NMS
    std::sort(proposals.begin(), proposals.end(), [](const Object& a, const Object& b) { return a.prob > b.prob; });
    std::vector<Object> objects;
    std::vector<int> picked;
    for (size_t i = 0; i < proposals.size(); i++) {
        int keep = 1;
        for (size_t j = 0; j < picked.size(); j++) {
            Object& b = proposals[picked[j]];
            float inter_area = intersection_area(proposals[i], b);
            float union_area = proposals[i].w * proposals[i].h + b.w * b.h - inter_area;
            if (inter_area / union_area > nms_threshold) {
                keep = 0;
                break;
            }
        }
        if (keep) {
            picked.push_back(i);
            objects.push_back(proposals[i]);
        }
    }

    jclass objClass = env->FindClass("com/example/yolodemo/YoloObject");
    jmethodID objId = env->GetMethodID(objClass, "<init>", "(FFFFIF)V");
    jobjectArray jObjArray = env->NewObjectArray(objects.size(), objClass, NULL);
    for (size_t i = 0; i < objects.size(); i++) {
        jobject jObj = env->NewObject(objClass, objId, objects[i].x, objects[i].y, objects[i].w, objects[i].h, objects[i].label, objects[i].prob);
        env->SetObjectArrayElement(jObjArray, i, jObj);
        env->DeleteLocalRef(jObj);
    }
    return jObjArray;
}