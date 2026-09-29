package com.example.data.model

/** Which downloader performs the transfer for a task. */
enum class DownloadEngine {
    /** Plain HTTP(S) transfer with resume support, for direct media URLs. */
    HTTP,

    /** The bundled on-device yt-dlp binary: extracts, downloads and muxes streams. */
    YTDLP
}
