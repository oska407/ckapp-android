# 保留 JS Bridge 接口方法（addJavascriptInterface 反射调用，minify 不能剥）
-keepclassmembers class com.ckapp.checkin.CKAppBridge {
    @android.webkit.JavascriptInterface <methods>;
}
