#include <jni.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <vector>

#define LOG_TAG "di_vision"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/// 变化检测：输入两帧工作分辨率 RGBA 像素，先降采样到 64x64 再灰度差分，返回 [0,1]
extern "C" JNIEXPORT jfloat JNICALL
Java_com_deltainventory_app_vision_VisionEngine_nativeChangeScore(
    JNIEnv* env, jobject, jintArray a, jintArray b, jint w, jint h) {
    const jsize n = env->GetArrayLength(a);
    if (n <= 0 || env->GetArrayLength(b) != n || w <= 0 || h <= 0) return 1.0f;
    std::vector<int32_t> pa(n), pb(n);
    env->GetIntArrayRegion(a, 0, n, pa.data());
    env->GetIntArrayRegion(b, 0, n, pb.data());

    // 直接在原始 buffer 上按步长采样到 64x64，避免构造全尺寸 Mat
    const int DS = 64;
    cv::Mat ga(DS, DS, CV_8UC1), gb(DS, DS, CV_8UC1);
    const float sx = (float)w / DS, sy = (float)h / DS;
    for (int dy = 0; dy < DS; ++dy) {
        const int sy0 = std::min((int)(dy * sy), h - 1);
        for (int dx = 0; dx < DS; ++dx) {
            const int sx0 = std::min((int)(dx * sx), w - 1);
            const int idx = sy0 * w + sx0;
            uint32_t ca = (uint32_t)pa[idx], cb = (uint32_t)pb[idx];
            ga.at<uint8_t>(dy, dx) = (uint8_t)(((ca>>16&0xFF)*77 + (ca>>8&0xFF)*150 + (ca&0xFF)*29)>>8);
            gb.at<uint8_t>(dy, dx) = (uint8_t)(((cb>>16&0xFF)*77 + (cb>>8&0xFF)*150 + (cb&0xFF)*29)>>8);
        }
    }
    cv::Mat diff;
    cv::absdiff(ga, gb, diff);
    return (jfloat)(cv::mean(diff)[0] / 255.0);
}

/// 感知哈希：输入工作图，直接降采样到 9x8 灰度做 dHash，避免全尺寸 Mat
extern "C" JNIEXPORT jlong JNICALL
Java_com_deltainventory_app_vision_VisionEngine_nativePhash(
    JNIEnv* env, jobject, jintArray pixels, jint w, jint h) {
    const jsize n = env->GetArrayLength(pixels);
    if (n <= 0 || w <= 0 || h <= 0) return 0;
    std::vector<int32_t> p(n);
    env->GetIntArrayRegion(pixels, 0, n, p.data());
    const int PW = 9, PH = 8;
    cv::Mat small(PH, PW, CV_8UC1);
    const float sx = (float)w / PW, sy = (float)h / PH;
    for (int dy = 0; dy < PH; ++dy) {
        int sy0 = std::min((int)(dy * sy), h - 1);
        for (int dx = 0; dx < PW; ++dx) {
            int sx0 = std::min((int)(dx * sx), w - 1);
            uint32_t c = (uint32_t)p[sy0 * w + sx0];
            small.at<uint8_t>(dy, dx) = (uint8_t)(((c>>16&0xFF)*77 + (c>>8&0xFF)*150 + (c&0xFF)*29)>>8);
        }
    }
    uint64_t hash = 0;
    for (int y = 0; y < 8; ++y)
        for (int x = 0; x < 8; ++x)
            hash = (hash << 1) | (small.at<uint8_t>(y,x) > small.at<uint8_t>(y,x+1) ? 1ULL : 0ULL);
    return (jlong)hash;
}
