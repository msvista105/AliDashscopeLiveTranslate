package com.sxm.livetranslate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sxm.livetranslate.service.TranslateManager
import com.sxm.livetranslate.service.TranslateService

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = TranslateManager.get(app)
    val state = manager.state
    val segments = manager.segments

    fun start() {
        TranslateService.start(getApplication())
        manager.start()
    }

    fun stop() {
        manager.stop()
        TranslateService.stop(getApplication())
    }

    override fun onCleared() {
        // 会话由前台 Service + Manager 单例持有，ViewModel 销毁不结束会话
    }
}
