package com.github.jing332.script.simple

import com.github.jing332.common.utils.ChajianDir
import com.github.jing332.script.runtime.Environment
import com.github.jing332.script.runtime.RhinoScriptRuntime
import com.github.jing332.script.simple.ext.JsExtensions

class CompatScriptRuntime(val ttsrv: JsExtensions) :
    RhinoScriptRuntime(
        environment = Environment(
            // 脚本沙箱根目录：Android/data/<包名>/files/chajian（由 ChajianDir 统一解析，零权限）
            ChajianDir.rootPath,
            ttsrv.engineId
        )
    ) {
    override fun init() {
        super.init()
        globalScope.defineGetter("ttsrv", ::ttsrv)
    }
}