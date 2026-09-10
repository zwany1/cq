package com.zengqi.ai.network.tts

interface ConfigurableTtsProvider {
    fun updateConfig(config: TtsConfig)
}