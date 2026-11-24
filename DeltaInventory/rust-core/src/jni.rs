//! JNI 桥接：把 Rust 核心导出给 Android Kotlin。
//! 仅在 target_os = "android" 时编译。

#[cfg(target_os = "android")]
mod android {
    use crate::{optimize_all, PackItem};
    use jni::JNIEnv;
    use jni::objects::JString;
    use jni::sys::jstring;

    #[no_mangle]
    pub extern "C" fn Java_com_deltainventory_app_domain_CoreBridge_nativeVersion(
        env: JNIEnv, _: jni::objects::JClass,
    ) -> jstring {
        let out = env.new_string(crate::version()).expect("create string");
        out.into_raw()
    }

    #[no_mangle]
    pub extern "C" fn Java_com_deltainventory_app_domain_CoreBridge_nativeOptimize(
        mut env: JNIEnv, _: jni::objects::JClass,
        grid_w: i32, grid_h: i32, items_json: JString,
    ) -> jstring {
        let json: String = env.get_string(&items_json).map(|s| s.into()).unwrap_or_default();
        let items: Vec<PackItem> = serde_json::from_str(&json).unwrap_or_default();
        let result = optimize_all(grid_w as u32, grid_h as u32, &items);
        let out = serde_json::to_string(&result).unwrap_or_else(|_| "{}".into());
        env.new_string(out).expect("create string").into_raw()
    }
}
