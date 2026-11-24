#include <jni.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <vector>

#define LOG_TAG "di_vision"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/// 图像变化检测：输入两帧 RGBA 像素(IntArray)，返回归一化差异分数 [0,1]
extern "C" JNIEXPORT jfloat JNICALL
Java_com_deltainventory_app_vision_VisionEngine_nativeChangeScore(
    JNIEnv* env, jobject /*thiz*/, jintArray a, jintArray b, jint w, jint h) {
    jsize n = env->GetArrayLength(a);
    if (n <= 0 || env->GetArrayLength(b) != n) return 1.0f;
    std::vector<int32_t> pa(n), pb(n);
    env->GetIntArrayRegion(a, 0, n, pa.data());
    env->GetIntArrayRegion(b, 0, n, pb.data());

    // ARGB_8888 -> BGR cv::Mat
    cv::Mat ma(h, w, CV_8UC3), mb(h, w, CV_8UC3);
    for (int i = 0; i < n; ++i) {
        int y = i / w, x = i % w;
        uint32_t ca = (uint32_t)pa[i], cb = (uint32_t)pb[i];
        ma.at<cv::Vec3b>(y,x) = cv::Vec3b(ca & 0xFF, (ca>>8)&0xFF, (ca>>16)&0xFF);
        mb.at<cv::Vec3b>(y,x) = cv::Vec3b(cb & 0xFF, (cb>>8)&0xFF, (cb>>16)&0xFF);
    }
    // 灰度 + 绝对差分 + 均值
    cv::Mat ga, gb, diff;
    cv::cvtColor(ma, ga, cv::COLOR_BGR2GRAY);
    cv::cvtColor(mb, gb, cv::COLOR_BGR2GRAY);
    cv::absdiff(ga, gb, diff);
    cv::Scalar mean = cv::mean(diff);
    return (jfloat)(mean[0] / 255.0);
}

/// 感知哈希（OpenCV resize + 灰度 + dHash），返回 64bit
extern "C" JNIEXPORT jlong JNICALL
Java_com_deltainventory_app_vision_VisionEngine_nativePhash(
    JNIEnv* env, jobject /*thiz*/, jintArray pixels, jint w, jint h) {
    jsize n = env->GetArrayLength(pixels);
    if (n <= 0) return 0;
    std::vector<int32_t> p(n);
    env->GetIntArrayRegion(pixels, 0, n, p.data());
    cv::Mat src(h, w, CV_8UC3);
    for (int i = 0; i < n; ++i) {
        int y = i / w, x = i % w;
        uint32_t c = (uint32_t)p[i];
        src.at<cv::Vec3b>(y,x) = cv::Vec3b(c & 0xFF, (c>>8)&0xFF, (c>>16)&0xFF);
    }
    cv::Mat gray, small;
    cv::cvtColor(src, gray, cv::COLOR_BGR2GRAY);
    cv::resize(gray, small, cv::Size(9,8), 0, 0, cv::INTER_AREA);
    uint64_t hash = 0;
    for (int y=0;y<8;y++) for(int x=0;x<8;x++) {
        hash = (hash<<1) | (small.at<uint8_t>(y,x) > small.at<uint8_t>(y,x+1) ? 1ULL : 0ULL);
    }
    return (jlong)hash;
}
